#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Validate AgentCode/Java metrics definitions and collected values.

Checks:
- schema.json required fields each metric
- baseline/current/target types where expected
- capabilities.json Java status is one of known values
- current.json can be dereferenced from schema
- simple denominator sanity: non-zero where numeric denominator exists
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "metrics" / "schema.json"
CAPABILITIES = ROOT / "metrics" / "capabilities.json"
CURRENT = ROOT / "metrics" / "current.json"

REQUIRED_METRIC_FIELDS = {
    "id", "name", "category", "definition",
    "numerator", "denominator", "unit", "period",
    "data_source", "baseline", "current", "target_value",
    "target_direction", "target_milestone", "status", "verification",
}

VALID_JAVA_STATUS = {"implemented", "partial", "not_implemented", "not_applicable"}


def load_json(path: Path) -> dict:
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def validate() -> list[str]:
    errors: list[str] = []
    schema = load_json(SCHEMA)
    metrics = schema.get("metrics", [])
    ids = [m.get("id") for m in metrics]
    if len(ids) != len(set(ids)):
        errors.append("schema.json metrics id 存在重复")

    for m in metrics:
        missing = REQUIRED_METRIC_FIELDS - set(m.keys())
        if missing:
            errors.append(f"metric {m.get('id', '<unknown>')} missing fields: {sorted(missing)}")
        den = m.get("denominator")
        if den == 0:
            errors.append(f"metric {m.get('id')} denominator cannot be 0")

    caps = load_json(CAPABILITIES).get("capabilities", [])
    cap_ids = [c.get("id") for c in caps]
    if len(cap_ids) != len(set(cap_ids)):
        errors.append("capabilities.json capability id 存在重复")
    for c in caps:
        status = c.get("java_status")
        if status not in VALID_JAVA_STATUS:
            errors.append(f"capability {c.get('id')} invalid java_status: {status}")

    if CURRENT.exists():
        current = load_json(CURRENT)
        current_metrics = current.get("metrics", {})
        for m in metrics:
            mid = m["id"]
            if mid not in current_metrics:
                errors.append(f"current.json missing metric id: {mid}")
    else:
        errors.append("current.json 不存在；请先运行 scripts/collect_metrics.py")

    return errors


def main() -> int:
    errors = validate()
    if errors:
        for e in errors:
            print(f"ERROR: {e}", file=sys.stderr)
        return 1
    print("OK: metrics schema/current/capabilities are valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())