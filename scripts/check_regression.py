#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Compare a candidate real-LLM baseline against the committed golden baseline.

Used by CI (llm-regression job) to fail on performance/cost regressions WITHOUT
overwriting the committed golden unless a human promotes the new numbers.

Exit code 0 = within thresholds, 1 = regression detected, 2 = bad input.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# 相对 golden 的可容忍劣化比例（>1 表示允许比 golden 差多少）
DEFAULT_TOLERANCE_P95 = 1.20     # 时延 p50/p95 最多 +20%
DEFAULT_TOLERANCE_TOKENS = 1.20  # 成本最多 +20%
MIN_SUCCESS_DROP_PP = 5.0        # 成功率允许回退的最大百分点


def load(path: Path) -> dict:
    if not path.exists():
        print(f"ERROR: file not found: {path}", file=sys.stderr)
        sys.exit(2)
    return json.loads(path.read_text(encoding="utf-8"))


def value_of(baseline: dict, key: str):
    return baseline.get("metrics", {}).get(key, {}).get("value")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden", default="metrics/real_llm_baseline.json")
    ap.add_argument("--candidate", required=True)
    ap.add_argument("--tolerance-latency", type=float, default=DEFAULT_TOLERANCE_P95)
    ap.add_argument("--tolerance-tokens", type=float, default=DEFAULT_TOLERANCE_TOKENS)
    args = ap.parse_args()

    golden = load(Path(args.golden))
    cand = load(Path(args.candidate))

    failures: list[str] = []
    report: list[str] = []

    g_succ = value_of(golden, "runtime.real.run_success_rate")
    c_succ = value_of(cand, "runtime.real.run_success_rate")
    if g_succ is not None and c_succ is not None:
        drop = g_succ - c_succ
        ok = drop <= MIN_SUCCESS_DROP_PP
        report.append(f"run_success_rate: golden={g_succ}% candidate={c_succ}% drop={drop:.2f}pp -> {'OK' if ok else 'REGRESSION'}")
        if not ok:
            failures.append(f"run_success_rate 回退超过 {MIN_SUCCESS_DROP_PP} 个百分点")

    higher_is_worse = {
        "runtime.real.run_duration_ms_p50": args.tolerance_latency,
        "runtime.real.run_duration_ms_p95": args.tolerance_latency,
        "runtime.real.ai_call_duration_ms_p50": args.tolerance_latency,
        "runtime.real.ai_call_duration_ms_p95": args.tolerance_latency,
        "runtime.real.tokens_per_run": args.tolerance_tokens,
    }
    for key, tol in higher_is_worse.items():
        g = value_of(golden, key)
        c = value_of(cand, key)
        if g is None or c is None:
            report.append(f"{key}: golden={g} candidate={c} -> SKIP (missing)")
            continue
        limit = g * tol
        ok = c <= limit
        report.append(f"{key}: golden={g} candidate={c} limit(<= {limit:.1f}) -> {'OK' if ok else 'REGRESSION'}")
        if not ok:
            failures.append(f"{key} 超过 golden 的 {tol:.0%}（{c} > {limit:.1f}）")

    if cand.get("sample", {}).get("attribution") != "runId_exact":
        report.append("WARNING: candidate 归因不是 runId_exact（可能混入旧格式日志）")

    print("Regression check report:")
    for line in report:
        print("  " + line)

    if failures:
        print("\nFAIL: 性能/成本回归：", file=sys.stderr)
        for f in failures:
            print("  - " + f, file=sys.stderr)
        return 1
    print("\nPASS: 候选基线在容忍范围内。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
