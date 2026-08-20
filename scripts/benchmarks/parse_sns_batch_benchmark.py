#!/usr/bin/env python3
"""JMH SNS batch 결과를 검증 가능한 low-cardinality 요약으로 변환한다."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from pathlib import Path
from typing import Any


def finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{label} must be a finite number")
    return float(value)


def raw_values(metric: dict[str, Any]) -> list[float]:
    values: list[float] = []
    for row in metric.get("rawData", []):
        if not isinstance(row, list):
            raise ValueError("metric.rawData rows must be arrays")
        values.extend(finite_number(value, "metric.rawData") for value in row)
    if not values:
        values.append(finite_number(metric.get("score"), "metric.score"))
    return values


def metric(record: dict[str, Any], name: str) -> dict[str, Any] | None:
    value = record.get("secondaryMetrics", {}).get(name)
    return value if isinstance(value, dict) else None


def percentile(record: dict[str, Any], percentile_key: str) -> float | None:
    values = record.get("primaryMetric", {}).get("scorePercentiles", {})
    value = values.get(percentile_key)
    return None if value is None else finite_number(value, f"primaryMetric.scorePercentiles[{percentile_key}]")


def to_ns(score: float, unit: str) -> float:
    multipliers = {"ns/op": 1.0, "us/op": 1_000.0, "µs/op": 1_000.0, "ms/op": 1_000_000.0, "s/op": 1_000_000_000.0}
    if unit not in multipliers:
        raise ValueError(f"latency unit is unsupported: {unit}")
    return score * multipliers[unit]


def parse_record(record: dict[str, Any]) -> dict[str, Any]:
    params = record.get("params")
    if not isinstance(params, dict):
        raise ValueError("record.params is required")
    entry_count = int(params["entryCount"])
    max_in_flight = int(params["maxInFlightBatches"])
    scenario = str(params.get("scenario", "success"))
    if scenario not in {"success", "transport"}:
        raise ValueError(f"unsupported SNS benchmark scenario: {scenario}")
    if entry_count <= 0 or max_in_flight <= 0:
        raise ValueError("entryCount and maxInFlightBatches must be positive")

    primary = record.get("primaryMetric")
    if not isinstance(primary, dict):
        raise ValueError("record.primaryMetric is required")
    score = finite_number(primary.get("score"), "primaryMetric.score")
    unit = str(primary.get("scoreUnit", ""))
    mode = str(record.get("mode", ""))
    if mode == "thrpt":
        if unit == "ops/s":
            messages_per_second = score * entry_count
        elif unit == "ops/ms":
            messages_per_second = score * 1_000.0 * entry_count
        else:
            raise ValueError(f"throughput unit is unsupported: {unit}")
    else:
        messages_per_second = None

    active_after_metric = metric(record, "activeAfter")
    max_active_metric = metric(record, "maxActive")
    completed_metric = metric(record, "completedEntries")
    completed_ids_metric = metric(record, "completedEntryIds")
    peak_heap_metric = metric(record, "peakHeapBytes")
    alloc_metric = metric(record, "gc.alloc.rate.norm")

    active_after = max(raw_values(active_after_metric), default=0.0) if active_after_metric else None
    max_active = max(raw_values(max_active_metric), default=0.0) if max_active_metric else None
    completed_entries = max(raw_values(completed_metric), default=0.0) if completed_metric else None
    completed_entry_ids = max(raw_values(completed_ids_metric), default=0.0) if completed_ids_metric else None
    peak_heap = max(raw_values(peak_heap_metric), default=0.0) if peak_heap_metric else None
    allocation = finite_number(alloc_metric.get("score"), "gc.alloc.rate.norm.score") if alloc_metric else None

    latency = {
        "p50_ns": None,
        "p95_ns": None,
        "p99_ns": None,
    }
    if mode == "avgt":
        latency = {
            "p50_ns": to_ns(percentile(record, "50.0") or score, unit),
            "p95_ns": to_ns(percentile(record, "95.0") or score, unit),
            "p99_ns": to_ns(percentile(record, "99.0") or score, unit),
        }

    return {
        "key": f"{scenario}:{entry_count}:{max_in_flight}",
        "scenario": scenario,
        "entry_count": entry_count,
        "expected_chunks": (entry_count + 9) // 10,
        "max_in_flight": max_in_flight,
        "mode": mode,
        "throughput_messages_per_second": messages_per_second,
        "latency": latency,
        "memory": {
            "allocation_bytes_per_operation": allocation,
            "peak_heap_bytes_sample": peak_heap,
        },
        "cleanup": {
            "active_after": active_after,
            "max_active": max_active,
            "completed_entry_observations": completed_entries,
            "completed_entry_id_observations": completed_entry_ids,
            "pending_roots": None,
            "completed_entry_roots": None,
        },
    }


def read_records(paths: list[Path]) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in paths:
        payload = json.loads(path.read_text())
        if not isinstance(payload, list):
            raise ValueError(f"{path} must contain a JMH array")
        records.extend(payload)
    return records


def combine(records: list[dict[str, Any]]) -> dict[str, Any]:
    rows: dict[str, dict[str, Any]] = {}
    for record in records:
        row = parse_record(record)
        existing = rows.get(row["key"])
        if existing is None:
            rows[row["key"]] = row
            continue
        if row["mode"] == existing["mode"]:
            raise ValueError(f"duplicate SNS benchmark metric for {row['key']} ({row['mode']})")
        if row["mode"] == "thrpt":
            if existing["throughput_messages_per_second"] is not None:
                raise ValueError(f"duplicate SNS throughput metric for {row['key']}")
            existing["throughput_messages_per_second"] = row["throughput_messages_per_second"]
            existing["memory"] = row["memory"]
            existing["cleanup"] = row["cleanup"]
        elif row["mode"] == "avgt":
            if any(value is not None for value in existing["latency"].values()):
                raise ValueError(f"duplicate SNS latency metric for {row['key']}")
            existing["latency"] = row["latency"]
        else:
            raise ValueError(f"unsupported duplicate SNS benchmark mode: {row['mode']}")

    ordered = [
        rows[key]
        for key in sorted(
            rows,
            key=lambda value: (
                value.split(":")[0],
                int(value.split(":")[1]),
                int(value.split(":")[2]),
            ),
        )
    ]
    throughput_values = [
        row["throughput_messages_per_second"]
        for row in ordered
        if row["scenario"] == "success" and row["throughput_messages_per_second"] is not None
    ]
    p95_values = [row["latency"]["p95_ns"] for row in ordered if row["latency"]["p95_ns"] is not None]
    peak_values = [row["memory"]["peak_heap_bytes_sample"] for row in ordered if row["memory"]["peak_heap_bytes_sample"] is not None]
    return {
        "schema_version": 1,
        "rows": ordered,
        "aggregate": {
            "throughput_messages_per_second_median": statistics.median(throughput_values) if throughput_values else None,
            "latency_p95_ns_max": max(p95_values) if p95_values else None,
            "peak_heap_bytes_max": max(peak_values) if peak_values else None,
        },
    }


def validate_complete_matrix(summary: dict[str, Any]) -> None:
    expected = {
        f"{scenario}:{entry_count}:{max_in_flight}"
        for scenario in ("success", "transport")
        for entry_count in (1, 10, 11, 20, 21, 100)
        for max_in_flight in (1, 2, 4)
    }
    actual = {row["key"] for row in summary["rows"]}
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    if missing or extra or len(summary["rows"]) != len(expected):
        raise ValueError(f"SNS benchmark matrix mismatch; missing={missing}, extra={extra}")
    for row in summary["rows"]:
        if row["throughput_messages_per_second"] is None:
            raise ValueError(f"throughput is missing for {row['key']}")
        if any(value is None for value in row["latency"].values()):
            raise ValueError(f"latency percentiles are missing for {row['key']}")
        if row["cleanup"]["active_after"] not in (None, 0.0):
            raise ValueError(f"active publisher cleanup failed for {row['key']}")
        if row["cleanup"]["max_active"] is not None and row["cleanup"]["max_active"] > row["max_in_flight"]:
            raise ValueError(f"maxInFlight bound failed for {row['key']}")


def compare(candidate: dict[str, Any], baseline: dict[str, Any], minimum_improvement: float, regression_threshold: float) -> dict[str, Any]:
    baseline_rows = {row["key"]: row for row in baseline["rows"]}
    failures: list[str] = []
    deltas: list[float] = []
    for row in candidate["rows"]:
        reference = baseline_rows.get(row["key"])
        if reference is None:
            failures.append(f"missing baseline row {row['key']}")
            continue
        current = row["throughput_messages_per_second"]
        previous = reference["throughput_messages_per_second"]
        if current is not None and previous:
            delta = (current - previous) / previous
            deltas.append(delta)
            if delta < minimum_improvement:
                failures.append(f"throughput regression/under-target {row['key']}: {delta:.4f}")
        for field in ("p95_ns", "p99_ns"):
            current_latency = row["latency"].get(field)
            previous_latency = reference["latency"].get(field)
            if current_latency is not None and previous_latency and current_latency > previous_latency * (1.0 + regression_threshold):
                failures.append(f"latency regression {row['key']} {field}")
        current_heap = row["memory"].get("peak_heap_bytes_sample")
        previous_heap = reference["memory"].get("peak_heap_bytes_sample")
        if current_heap is not None and previous_heap and current_heap > previous_heap * (1.0 + regression_threshold):
            failures.append(f"heap regression {row['key']}")
        if row["cleanup"].get("active_after") not in (None, 0.0):
            failures.append(f"active publisher leak {row['key']}")
        if row["cleanup"].get("max_active") is not None and row["cleanup"]["max_active"] > row["max_in_flight"]:
            failures.append(f"maxInFlight bound violation {row['key']}")
    candidate["comparison"] = {
        "decision": "accepted" if not failures and deltas and statistics.median(deltas) >= minimum_improvement else "rejected",
        "median_throughput_delta": statistics.median(deltas) if deltas else None,
        "failures": failures,
    }
    return candidate


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", action="append", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--require-complete-matrix", action="store_true")
    parser.add_argument("--minimum-improvement", type=float, default=0.01)
    parser.add_argument("--regression-threshold", type=float, default=0.10)
    args = parser.parse_args()

    summary = combine(read_records(args.input))
    if args.require_complete_matrix:
        validate_complete_matrix(summary)
    if args.baseline:
        baseline = json.loads(args.baseline.read_text())
        summary = compare(summary, baseline, args.minimum_improvement, args.regression_threshold)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    json.loads(temporary.read_text())
    temporary.replace(args.output)


if __name__ == "__main__":
    main()
