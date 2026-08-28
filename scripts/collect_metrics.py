#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Collect AgentCode/Java quantitative metric baselines.

Reads:
- metrics/schema.json        metric definitions
- metrics/capabilities.json  Java/Python parity matrix
- target/surefire-reports/   Maven test results
- logs/agentcode.log         AUDIT_AGENT_RUN / AUDIT_APPROVAL_TIMEOUT logs

Writes:
- metrics/current.json       current measured values that can be reproduced
"""

from __future__ import annotations

import glob
import json
import math
import os
import re
import statistics
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "metrics" / "schema.json"
CAPABILITIES = ROOT / "metrics" / "capabilities.json"
CURRENT = ROOT / "metrics" / "current.json"
REAL_BASELINE = ROOT / "metrics" / "real_llm_baseline.json"
SUREFIRE_GLOB = ROOT / "target" / "surefire-reports" / "TEST-*.xml"
LOG_FILE = ROOT / "logs" / "agentcode.log"

AUDIT_RUN_RE = re.compile(
    r"AUDIT_AGENT_RUN .*?result=(?P<result>COMPLETED|ERROR)"
    r"\s+durationMs=(?P<durationMs>\d+)"
    r"\s+events=(?P<events>\d+)"
    r"\s+toolEvents=(?P<toolEvents>\d+)"
    r"\s+permissionRequests=(?P<permissionRequests>\d+)"
)
AUDIT_AI_STREAM_RE = re.compile(
    r"AUDIT_AI_STREAM (?:runId=(?P<runId>\S+) )?model=(?P<model>\S+)"
    r"\s+durationMs=(?P<durationMs>\d+)"
)
APPROVAL_TIMEOUT_RE = re.compile(r"AUDIT_APPROVAL_TIMEOUT")


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def load_real_baseline() -> dict | None:
    """Optional N>=20 real-LLM baseline produced by scripts/bench_baseline.py."""
    if REAL_BASELINE.exists():
        try:
            return load_json(REAL_BASELINE)
        except Exception:
            return None
    return None


def percentile(sorted_values: list, pct: float):
    """Nearest-rank percentile (ceil), safe for tiny samples."""
    if not sorted_values:
        return None
    rank = math.ceil(pct / 100 * len(sorted_values))
    idx = min(max(rank - 1, 0), len(sorted_values) - 1)
    return sorted_values[idx]


def parse_surefire() -> dict:
    tests = failures = errors = skipped = 0
    for f in glob.glob(str(SUREFIRE_GLOB)):
        root = ET.parse(f).getroot()
        tests += int(root.attrib.get("tests", 0))
        failures += int(root.attrib.get("failures", 0))
        errors += int(root.attrib.get("errors", 0))
        skipped += int(root.attrib.get("skipped", 0))
    passed = tests - failures - errors - skipped
    return {
        "total_tests": tests,
        "passed_tests": passed,
        "failed_tests": failures + errors,
        "skipped_tests": skipped,
        "test_pass_rate": round(passed / tests * 100, 2) if tests else None,
    }


def parse_runtime_log() -> dict:
    runs = []
    ai_durations = []
    timeout_count = 0
    if LOG_FILE.exists():
        text = LOG_FILE.read_text(encoding="utf-8", errors="ignore")
        for m in AUDIT_RUN_RE.finditer(text):
            runs.append({
                "result": m.group("result"),
                "duration_ms": int(m.group("durationMs")),
                "events": int(m.group("events")),
                "tool_events": int(m.group("toolEvents")),
                "permission_requests": int(m.group("permissionRequests")),
            })
        ai_durations = [int(m.group("durationMs")) for m in AUDIT_AI_STREAM_RE.finditer(text)]
        timeout_count = len(APPROVAL_TIMEOUT_RE.findall(text))

    total_runs = len(runs)
    completed = sum(1 for r in runs if r["result"] == "COMPLETED")
    durations = sorted(r["duration_ms"] for r in runs)
    p50 = percentile(durations, 50)
    p95 = percentile(durations, 95)

    ai = sorted(ai_durations)
    ai_p50 = percentile(ai, 50)
    ai_p95 = percentile(ai, 95)

    return {
        "total_runs": total_runs,
        "completed_runs": completed,
        "run_success_rate": round(completed / total_runs * 100, 2) if total_runs else None,
        "run_duration_ms_p50": p50,
        "run_duration_ms_p95": p95,
        "ai_call_count": len(ai_durations),
        "ai_call_duration_ms_p50": ai_p50,
        "ai_call_duration_ms_p95": ai_p95,
        "events_per_run_avg": round(sum(r["events"] for r in runs) / total_runs, 2) if total_runs else None,
        "tool_events_per_run_avg": round(sum(r["tool_events"] for r in runs) / total_runs, 2) if total_runs else None,
        "permission_per_run_avg": round(sum(r["permission_requests"] for r in runs) / total_runs, 2) if total_runs else None,
        "interrupted_runs": sum(1 for r in runs if r["permission_requests"] > 0),
        "approval_timeout_count": timeout_count,
    }


SAFETY_TEST_XML = ROOT / "target" / "surefire-reports" / "TEST-com.agentcode.dto.SafetyPolicyCoverageTest.xml"


def parse_safety_tests() -> dict:
    """Derive safety coverage from executed SafetyPolicyCoverageTest cases.

    The dangerous / outside-cwd cases enumerate the full policy set by construction
    (guarded by dangerousListIsFullyCovered / outsideCwdSamplesCoverEveryBuiltinPattern),
    so a green run means 100% coverage of the *current* set. Decision coverage is checked
    against the fixed 4-value contract (guarded by decisionCoverageIsComplete).
    """
    result = {
        "present": False,
        "failures": None,
        "errors": None,
        "dangerous_cases": 0,
        "outside_cwd_cases": 0,
        "decision_cases": 0,
        "deny_rule_coverage": None,
        "path_traversal_blocked": None,
        "decision_coverage": None,
    }
    if not SAFETY_TEST_XML.exists():
        return result

    root = ET.parse(str(SAFETY_TEST_XML)).getroot()
    failures = int(root.attrib.get("failures", 0))
    errors = int(root.attrib.get("errors", 0))
    result["present"] = True
    result["failures"] = failures
    result["errors"] = errors

    dangerous = outside = decision = 0
    for tc in root.iter("testcase"):
        name = tc.attrib.get("name", "")
        if name.startswith("everyDangerousCommandIsDeniedAndNotAutoApproved"):
            dangerous += 1
        elif name.startswith("everyOutsideCwdSampleForcesManualApproval"):
            outside += 1
        elif name.startswith("everyApprovalDecisionIsResolvable"):
            decision += 1
    result["dangerous_cases"] = dangerous
    result["outside_cwd_cases"] = outside
    result["decision_cases"] = decision

    green = failures == 0 and errors == 0
    if green and dangerous > 0:
        result["deny_rule_coverage"] = 100.0  # numerator == denominator (full enumeration)
    if green and outside > 0:
        result["path_traversal_blocked"] = 100.0
    if green:
        # decision_coverage 固定契约分母 = 4（Decision 枚举）；若枚举扩容，
        # decisionCoverageIsComplete 会失败 -> green=False -> 不记 100。
        result["decision_coverage"] = round(min(decision, 4) / 4 * 100, 2)
    return result


JACOCO_CSV = ROOT / "target" / "site" / "jacoco" / "jacoco.csv"
CHECKSTYLE_XML = ROOT / "target" / "checkstyle-result.xml"


def parse_quality_reports() -> dict:
    """JaCoCo line/branch coverage + Checkstyle issue counts (absent -> None)."""
    out = {
        "line_coverage": None, "branch_coverage": None,
        "line_covered": None, "line_missed": None,
        "branch_covered": None, "branch_missed": None,
        "static_error": None, "static_warning": None, "static_total": None,
    }
    if JACOCO_CSV.exists():
        import csv as _csv
        lm = lc = bm = bc = 0
        with JACOCO_CSV.open(encoding="utf-8", errors="ignore") as f:
            for row in _csv.DictReader(f):
                lm += int(row["LINE_MISSED"]); lc += int(row["LINE_COVERED"])
                bm += int(row["BRANCH_MISSED"]); bc += int(row["BRANCH_COVERED"])
        if (lc + lm) > 0:
            out["line_coverage"] = round(lc / (lc + lm) * 100, 2)
        if (bc + bm) > 0:
            out["branch_coverage"] = round(bc / (bc + bm) * 100, 2)
        out["line_covered"], out["line_missed"] = lc, lm
        out["branch_covered"], out["branch_missed"] = bc, bm

    if CHECKSTYLE_XML.exists():
        root = ET.parse(str(CHECKSTYLE_XML)).getroot()
        err = warn = info = 0
        for fm in root.iter("file"):
            for e in fm.findall("error"):
                sev = e.get("severity", "warning")
                if sev == "error":
                    err += 1
                elif sev == "warning":
                    warn += 1
                else:
                    info += 1
        out["static_error"], out["static_warning"], out["static_total"] = err, warn, err + warn + info
    return out


def parse_capabilities() -> dict:
    caps = load_json(CAPABILITIES)["capabilities"]
    comparable = [c for c in caps if c.get("python_status") != "not_applicable"]
    score = 0.0
    implemented_count = 0
    partial_count = 0
    not_implemented_count = 0
    # parity 只针对“与 Python 可比”的能力项计分；Java-only 能力不参与分子/分母
    for c in comparable:
        status = c.get("java_status", "not_implemented")
        if status == "implemented":
            score += 1.0
            implemented_count += 1
        elif status == "partial":
            score += 0.5
            partial_count += 1
        else:
            not_implemented_count += 1
    total = len(comparable)
    parity_rate = round(score / total * 100, 2) if total else None
    return {
        "total_capabilities": len(caps),
        "comparable_to_python": total,
        "implemented_count": implemented_count,
        "partial_count": partial_count,
        "not_implemented_count": not_implemented_count,
        "parity_score": score,
        "parity_rate": parity_rate,
    }


def scan_secrets() -> int:
    if not LOG_FILE.exists():
        return 0
    text = LOG_FILE.read_text(encoding="utf-8", errors="ignore")
    patterns = [
        r"sk-[A-Za-z0-9]{20,}",
        r"api[_-]?key[\"']?\s*[:=]\s*[\"'][A-Za-z0-9_\-]{16,}",
        r"AKIA[0-9A-Z]{16}",
    ]
    matches = set()
    for pat in patterns:
        for m in re.finditer(pat, text, re.IGNORECASE):
            matches.add(m.group(0)[:8])
    return len(matches)


def main() -> None:
    schema = load_json(SCHEMA)
    surefire = parse_surefire()
    runtime = parse_runtime_log()
    caps = parse_capabilities()
    secrets = scan_secrets()

    by_id = {m["id"]: m for m in schema["metrics"]}
    values = {}

    if surefire["test_pass_rate"] is not None:
        values["quality.test_pass_rate"] = surefire["test_pass_rate"]
    values["quality.test_count"] = surefire["total_tests"]
    values["capability.parity_rate"] = caps["parity_rate"]
    safety = parse_safety_tests()
    if safety["deny_rule_coverage"] is not None:
        values["safety.deny_rule_coverage"] = safety["deny_rule_coverage"]
    if safety["path_traversal_blocked"] is not None:
        values["safety.path_traversal_blocked"] = safety["path_traversal_blocked"]
    if safety["decision_coverage"] is not None:
        values["safety.decision_coverage"] = safety["decision_coverage"]
    quality = parse_quality_reports()
    if quality["line_coverage"] is not None:
        values["quality.line_coverage"] = quality["line_coverage"]
    if quality["branch_coverage"] is not None:
        values["quality.branch_coverage"] = quality["branch_coverage"]
    if quality["static_total"] is not None:
        values["quality.static_issues"] = quality["static_total"]
    values["runtime.run_success_rate"] = runtime["run_success_rate"]
    values["runtime.run_duration_ms_p50"] = runtime["run_duration_ms_p50"]
    values["runtime.run_duration_ms_p95"] = runtime["run_duration_ms_p95"]
    values["runtime.ai_call_duration_ms_p50"] = runtime["ai_call_duration_ms_p50"]
    values["runtime.ai_call_duration_ms_p95"] = runtime["ai_call_duration_ms_p95"]
    values["runtime.permission_per_run"] = runtime["permission_per_run_avg"]
    if runtime["interrupted_runs"] and runtime["approval_timeout_count"] is not None:
        values["runtime.approval_timeout_rate"] = round(
            runtime["approval_timeout_count"] / runtime["interrupted_runs"] * 100, 2
        )
    values["safety.secrets_in_logs"] = secrets

    # 若存在专用真实 LLM 基线（bench_baseline.py 生成），用它覆盖运行类指标，
    # 因为这些是从隔离日志算出的稳定 p50/p95 与 tokens_per_run，比混合测试日志更权威。
    real = load_real_baseline()
    real_sources = {}
    real_sample = None
    if real:
        real_sample = real.get("sample")
        rm = real.get("metrics", {})
        mapping = {
            "runtime.run_success_rate": "runtime.real.run_success_rate",
            "runtime.run_duration_ms_p50": "runtime.real.run_duration_ms_p50",
            "runtime.run_duration_ms_p95": "runtime.real.run_duration_ms_p95",
            "runtime.ai_call_duration_ms_p50": "runtime.real.ai_call_duration_ms_p50",
            "runtime.ai_call_duration_ms_p95": "runtime.real.ai_call_duration_ms_p95",
            "runtime.tokens_per_run": "runtime.real.tokens_per_run",
            "runtime.permission_per_run": "runtime.real.approval_per_run",
        }
        for mid, rkey in mapping.items():
            entry = rm.get(rkey)
            if entry and entry.get("value") is not None:
                values[mid] = entry["value"]
                real_sources[mid] = "metrics/real_llm_baseline.json"

    for metric in schema["metrics"]:
        mid = metric["id"]
        if mid in values:
            metric["current"] = values[mid]
            if metric.get("baseline") is None and values[mid] is not None:
                metric["baseline"] = values[mid]
            metric["status"] = "collected"
            metric["measured_from"] = real_sources.get(mid, "logs/agentcode.log (mixed test+real)")

    current = {
        "schema_version": schema["schema_version"],
        "generated_at": __import__("datetime").datetime.now().astimezone().isoformat(),
        "project": schema["project"],
        "summary": {
            "surefire": surefire,
            "runtime_log": runtime,
            "real_llm": real_sample,
            "capabilities": caps,
            "safety_tests": safety,
            "quality_reports": quality,
            "secret_matches": secrets,
        },
        "metrics": by_id,
    }
    CURRENT.write_text(json.dumps(current, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Wrote {CURRENT}")
    if real_sample:
        print(f"  real_llm_baseline: {real_sample}")
    print(f"  test_pass_rate={surefire['test_pass_rate']} test_count={surefire['total_tests']}")
    print(f"  parity_rate={caps['parity_rate']} (implemented={caps['implemented_count']}, partial={caps['partial_count']}, not={caps['not_implemented_count']})")
    print(f"  run_success_rate={runtime['run_success_rate']} p50={runtime['run_duration_ms_p50']}ms p95={runtime['run_duration_ms_p95']}ms")
    print(f"  ai_calls={runtime['ai_call_count']} ai_p50={runtime['ai_call_duration_ms_p50']}ms ai_p95={runtime['ai_call_duration_ms_p95']}ms")
    print(f"  approval_timeouts={runtime['approval_timeout_count']} secret_matches={secrets}")


if __name__ == "__main__":
    main()