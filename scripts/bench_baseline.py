#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Parse a dedicated AgentCode/Java benchmark log into a real-LLM baseline.

Input : a log file containing AUDIT_AGENT_RUN_START / AUDIT_AGENT_RUN / AUDIT_AI_STREAM
        (start the server with LOG_FILE=<bench.log> so it is isolated from test logs).
Output: metrics/real_llm_baseline.json with ASU-style metrics
        (definition, numerator, denominator, value, period).

Attribution:
- AUDIT_AI_STREAM lines do not carry runId, so each AI event is attributed to the
  run segment [start_ts, end_ts] whose window contains it (segments are matched by
  runId FIFO). Requires the benchmark to run goals SEQUENTIALLY (bench_client.mjs does).
- tokens_per_run sums totalTokens across a run's AI calls = billed-token cost per run
  (later ReAct steps re-send prior context, so this is a cost metric, not unique tokens).
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from collections import defaultdict, deque
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

TS_RE = re.compile(r"^(?P<ts>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+08:00)")
START_RE = re.compile(r"AUDIT_AGENT_RUN_START runId=(?P<runId>[0-9a-fA-F-]+)")
END_RE = re.compile(
    r"AUDIT_AGENT_RUN runId=(?P<runId>[0-9a-fA-F-]+) .*?result=(?P<result>COMPLETED|ERROR) "
    r"durationMs=(?P<durationMs>\d+) "
    r"events=(?P<events>\d+) toolEvents=(?P<toolEvents>\d+) "
    r"permissionRequests=(?P<permissionRequests>\d+)"
)
AI_RE = re.compile(
    r"AUDIT_AI_STREAM (?:runId=(?P<runId>\S+) )?model=(?P<model>\S+) durationMs=(?P<durationMs>\d+) "
    r"promptMessages=(?P<promptMessages>\d+) chunks=(?P<chunks>\d+) "
    r"responseLength=(?P<responseLength>\d+) toolCalls=(?P<toolCalls>\d+) "
    r"promptTokens=(?P<promptTokens>\d+) completionTokens=(?P<completionTokens>\d+) "
    r"totalTokens=(?P<totalTokens>\d+)"
)


def parse_ts(line: str):
    m = TS_RE.match(line)
    if not m:
        return None
    return datetime.fromisoformat(m.group("ts"))


def percentile(sorted_values, pct):
    if not sorted_values:
        return None
    rank = math.ceil(pct / 100 * len(sorted_values))
    idx = min(max(rank - 1, 0), len(sorted_values) - 1)
    return sorted_values[idx]


def parse_log(path: Path):
    segments = []  # dict: runId,start,end,durationMs,result,events,toolEvents,perm
    open_starts = defaultdict(deque)
    ai_events = []  # dict: ts,durationMs,totalTokens,promptTokens,completionTokens
    models = set()

    with path.open(encoding="utf-8", errors="ignore") as f:
        for line in f:
            ts = parse_ts(line)
            sm = START_RE.search(line)
            if sm and ts:
                open_starts[sm.group("runId")].append(ts)
                continue
            em = END_RE.search(line)
            if em and ts:
                run_id = em.group("runId")
                start_ts = open_starts[run_id].popleft() if open_starts[run_id] else None
                segments.append({
                    "runId": run_id,
                    "start": start_ts,
                    "end": ts,
                    "durationMs": int(em.group("durationMs")),
                    "result": em.group("result"),
                    "events": int(em.group("events")),
                    "toolEvents": int(em.group("toolEvents")),
                    "perm": int(em.group("permissionRequests")),
                })
                continue
            am = AI_RE.search(line)
            if am and ts:
                rid = am.group("runId") or "-"
                ai_events.append({
                    "ts": ts,
                    "runId": rid,
                    "durationMs": int(am.group("durationMs")),
                    "totalTokens": int(am.group("totalTokens")),
                    "promptTokens": int(am.group("promptTokens")),
                    "completionTokens": int(am.group("completionTokens")),
                })
                models.add(am.group("model"))
    return segments, ai_events, sorted(models)


def attribute(segments, ai_events):
    """Assign each AI event to its run.

    Preferred: exact runId (AUDIT_AI_STREAM now carries it via Reactor Context), which
    is safe under concurrency. Fallback: time-window containment for legacy logs whose
    AI lines have no/`-` runId (requires sequential execution).
    """
    per_run_tokens = defaultdict(int)
    per_run_ai_calls = defaultdict(int)
    unattributed = 0
    window_fallbacks = 0
    known_runs = {s["runId"] for s in segments}
    usable = [s for s in segments if s["start"] is not None]

    for ev in ai_events:
        rid = ev.get("runId", "-")
        if rid and rid != "-" and rid in known_runs:
            target = rid
        else:
            hit = None
            for s in usable:
                if s["start"] <= ev["ts"] <= s["end"]:
                    hit = s
                    break
            if hit is None:
                unattributed += 1
                continue
            target = hit["runId"]
            window_fallbacks += 1
        per_run_tokens[target] += ev["totalTokens"]
        per_run_ai_calls[target] += 1
    return per_run_tokens, per_run_ai_calls, unattributed, window_fallbacks


def aggregate(segments, ai_events, per_run_tokens, per_run_ai_calls, unattributed, models,
              window_fallbacks=0):
    # group segments by logical run
    runs = defaultdict(list)
    for s in segments:
        runs[s["runId"]].append(s)

    run_durations = []
    run_token_sums = []
    successful_runs = 0
    runs_with_tokens = 0
    total_perm = 0
    for run_id, segs in runs.items():
        total_perm += sum(s["perm"] for s in segs)
        results = [s["result"] for s in segs]
        ok = all(r == "COMPLETED" for r in results)
        if ok:
            successful_runs += 1
            run_durations.append(sum(s["durationMs"] for s in segs))
        tok = per_run_tokens.get(run_id, 0)
        if ok and tok > 0:
            run_token_sums.append(tok)
            runs_with_tokens += 1

    ai_durations = sorted(e["durationMs"] for e in ai_events)
    n_runs = len(runs)

    def pct(vals):
        return percentile(vals, 95)

    baseline = {
        "schema_version": "1.0",
        "generated_at": datetime.now().astimezone().isoformat(),
        "models": models,
        "sample": {
            "total_runs": n_runs,
            "successful_runs": successful_runs,
            "total_ai_calls": len(ai_events),
            "total_segments": len(segments),
            "unattributed_ai_events": unattributed,
            "attribution": "runId_exact" if window_fallbacks == 0 else "runId+time_window_fallback",
            "window_fallback_count": window_fallbacks,
            "concurrency_safe": window_fallbacks == 0,
        },
        "metrics": {
            "runtime.real.run_success_rate": {
                "definition": "成功 run 数 / 总 run 数 * 100",
                "numerator": successful_runs,
                "denominator": n_runs,
                "value": round(successful_runs / n_runs * 100, 2) if n_runs else None,
                "unit": "percent",
            },
            "runtime.real.run_duration_ms_p50": {
                "definition": "每个逻辑 run 各段 durationMs 之和的 50 分位",
                "numerator": "p50(sum_segment_duration)",
                "denominator": "1",
                "value": percentile(sorted(run_durations), 50),
                "unit": "ms",
            },
            "runtime.real.run_duration_ms_p95": {
                "definition": "每个逻辑 run 各段 durationMs 之和的 95 分位",
                "numerator": "p95(sum_segment_duration)",
                "denominator": "1",
                "value": pct(sorted(run_durations)),
                "unit": "ms",
            },
            "runtime.real.ai_call_duration_ms_p50": {
                "definition": "AUDIT_AI_STREAM durationMs 的 50 分位",
                "numerator": "p50(ai_duration)",
                "denominator": "1",
                "value": percentile(ai_durations, 50),
                "unit": "ms",
            },
            "runtime.real.ai_call_duration_ms_p95": {
                "definition": "AUDIT_AI_STREAM durationMs 的 95 分位",
                "numerator": "p95(ai_duration)",
                "denominator": "1",
                "value": pct(ai_durations),
                "unit": "ms",
            },
            "runtime.real.tokens_per_run": {
                "definition": "成功且有关联 AI 调用的 run 内 totalTokens 之和 / 该 run 数",
                "numerator": sum(run_token_sums),
                "denominator": runs_with_tokens,
                "value": round(sum(run_token_sums) / runs_with_tokens, 1) if runs_with_tokens else None,
                "unit": "tokens/run",
            },
            "runtime.real.approval_per_run": {
                "definition": "permissionRequests 之和 / 总 run 数",
                "numerator": total_perm,
                "denominator": n_runs,
                "value": round(total_perm / n_runs, 2) if n_runs else None,
                "unit": "count/run",
            },
        },
    }
    return baseline


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", required=True, help="benchmark log file (AUDIT_* lines)")
    ap.add_argument("--out", default=str(ROOT / "metrics" / "real_llm_baseline.json"))
    args = ap.parse_args()

    log_path = Path(args.log)
    if not log_path.exists():
        print(f"ERROR: log not found: {log_path}", file=sys.stderr)
        return 1

    segments, ai_events, models = parse_log(log_path)
    if not segments:
        print("ERROR: no AUDIT_AGENT_RUN segments found in log", file=sys.stderr)
        return 1
    per_run_tokens, per_run_ai_calls, unattributed, window_fallbacks = attribute(segments, ai_events)
    baseline = aggregate(segments, ai_events, per_run_tokens, per_run_ai_calls, unattributed, models,
                         window_fallbacks)

    Path(args.out).write_text(json.dumps(baseline, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {args.out}")
    print(f"  models={models} total_runs={baseline['sample']['total_runs']} "
          f"success={baseline['sample']['successful_runs']} ai_calls={baseline['sample']['total_ai_calls']} "
          f"unattributed={unattributed} attribution={baseline['sample']['attribution']}")
    for mid, m in baseline["metrics"].items():
        print(f"  {mid} = {m['value']} {m['unit']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
