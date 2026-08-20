#!/usr/bin/env python3
"""SNS benchmark parser의 schema·guard 회귀 테스트."""

import json
import tempfile
import unittest
from pathlib import Path

from parse_sns_batch_benchmark import combine, compare, validate_complete_matrix


def record(
    mode: str,
    score: float,
    entry_count: int = 11,
    max_in_flight: int = 4,
    scenario: str = "success",
) -> dict:
    return {
        "mode": mode,
        "params": {
            "entryCount": str(entry_count),
            "maxInFlightBatches": str(max_in_flight),
            "scenario": scenario,
        },
        "primaryMetric": {
            "score": score,
            "scoreUnit": "ops/s" if mode == "thrpt" else "ns/op",
            "scorePercentiles": {"50.0": score, "95.0": score * 1.2, "99.0": score * 1.3},
            "rawData": [[score]],
        },
        "secondaryMetrics": {
            "activeAfter": {"score": 0, "rawData": [[0]]},
            "maxActive": {"score": 2, "rawData": [[2]]},
            "peakHeapBytes": {"score": 100, "rawData": [[100]]},
            "completedEntries": {"score": entry_count, "rawData": [[entry_count]]},
            "completedEntryIds": {"score": entry_count, "rawData": [[entry_count]]},
        },
    }


class ParserTest(unittest.TestCase):
    def test_combine_merges_throughput_and_latency(self) -> None:
        summary = combine([record("thrpt", 100), record("avgt", 1000)])
        row = summary["rows"][0]
        self.assertEqual(row["throughput_messages_per_second"], 1100.0)
        self.assertEqual(row["latency"]["p95_ns"], 1200.0)
        self.assertEqual(row["cleanup"]["max_active"], 2.0)
        self.assertEqual(row["cleanup"]["completed_entry_id_observations"], 11.0)

    def test_combine_keeps_transport_retention_row_separate(self) -> None:
        summary = combine(
            [
                record("thrpt", 100),
                record("avgt", 1000),
                record("thrpt", 50, scenario="transport"),
                record("avgt", 2000, scenario="transport"),
            ],
        )
        self.assertEqual(
            [row["key"] for row in summary["rows"]],
            ["success:11:4", "transport:11:4"],
        )

    def test_compare_rejects_bound_and_regression(self) -> None:
        baseline = combine([record("thrpt", 100), record("avgt", 1000)])
        candidate = combine([record("thrpt", 101), record("avgt", 1200)])
        candidate["rows"][0]["cleanup"]["max_active"] = 5.0
        result = compare(candidate, baseline, 0.01, 0.10)
        self.assertEqual(result["comparison"]["decision"], "rejected")
        self.assertTrue(result["comparison"]["failures"])

    def test_complete_matrix_rejects_sparse_input(self) -> None:
        with self.assertRaises(ValueError):
            validate_complete_matrix(combine([record("thrpt", 100), record("avgt", 1000)]))

    def test_combine_rejects_duplicate_metric_mode(self) -> None:
        with self.assertRaises(ValueError):
            combine([record("thrpt", 100), record("thrpt", 101)])


if __name__ == "__main__":
    unittest.main()
