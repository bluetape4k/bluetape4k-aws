#!/usr/bin/env python3
"""Executable regression tests for the Kinesis DryRun capability validator."""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from typing import Any


SCRIPT = Path(__file__).with_name("validate_kinesis_dry_run_capability.py")
SPEC = importlib.util.spec_from_file_location("validate_kinesis_dry_run_capability", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def rows(**overrides: Any) -> list[dict[str, Any]]:
    operations = (
        "PutRecord",
        "PutRecords",
        "GetShardIterator",
        "GetRecords",
    )
    result = []
    for index, operation in enumerate(operations):
        row: dict[str, Any] = {
            "schemaVersion": 1,
            "backend": "floci",
            "backendVersion": "test-1.0",
            "operation": operation,
            "status": "supported",
            "sanitizedReason": "dry_run_accepted",
            "streamToken": f"issue-620-{index}",
        }
        row.update(overrides.get(operation, {}))
        result.append(row)
    return result


class ValidateKinesisDryRunCapabilityTest(unittest.TestCase):
    def write_report(self, root: Path, report: Any) -> Path:
        path = root / "capability-floci.json"
        path.write_text(json.dumps(report), encoding="utf-8")
        return path

    def test_normalizes_four_rows_and_writes_only_allow_list(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            path = self.write_report(root, rows())

            output = MODULE.validate_and_write(path)

            self.assertEqual(root / "capability-floci.validated.json", output)
            normalized = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(4, len(normalized))
            self.assertEqual(
                {"schemaVersion", "backend", "backendVersion", "operation", "status", "sanitizedReason", "streamToken"},
                set(normalized[0]),
            )
            self.assertTrue(path.exists())

    def test_rejects_missing_duplicate_and_unknown_operations(self) -> None:
        cases = (
            rows()[:3],
            rows(**{"GetRecords": {"operation": "PutRecord"}}),
            rows(**{"GetRecords": {"unexpected": "value"}}),
        )
        for report in cases:
            with self.subTest(report=report):
                with tempfile.TemporaryDirectory() as temp_dir:
                    root = Path(temp_dir)
                    path = self.write_report(root, report)
                    with self.assertRaises(MODULE.CapabilityValidationError):
                        MODULE.validate_and_write(path)
                    self.assertFalse(path.exists())

    def test_rejects_unknown_fields_status_and_unsupported_reason(self) -> None:
        cases = (
            rows(**{"PutRecord": {"extra": "value"}}),
            rows(**{"PutRecord": {"status": "skipped"}}),
            rows(**{"PutRecord": {"status": "unsupported", "sanitizedReason": "normal_response"}}),
            rows(**{"PutRecord": {"sanitizedReason": "DRY_RUN_ACCEPTED"}}),
            rows(**{"GetRecords": {"backendVersion": "test-2.0"}}),
            rows(**{"PutRecords": {"status": ["supported"]}}),
        )
        for report in cases:
            with self.subTest(report=report):
                with tempfile.TemporaryDirectory() as temp_dir:
                    path = self.write_report(Path(temp_dir), report)
                    with self.assertRaises(MODULE.CapabilityValidationError):
                        MODULE.validate_and_write(path)
                    self.assertFalse(path.exists())

    def test_rejects_sensitive_marker_without_echoing_it(self) -> None:
        secret = "AWS_SECRET_ACCESS_KEY_SENTINEL"
        report = rows(**{"PutRecord": {"streamToken": secret}})
        with tempfile.TemporaryDirectory() as temp_dir:
            path = self.write_report(Path(temp_dir), report)
            output = io.StringIO()
            with contextlib.redirect_stderr(output):
                self.assertEqual(1, MODULE.main([str(SCRIPT), str(path)]))
            self.assertNotIn(secret, output.getvalue())
            self.assertEqual(
                "Kinesis DryRun capability validation failed (redacted).\n",
                output.getvalue(),
            )
            self.assertFalse(path.exists())

    def test_accepts_only_the_two_declared_backends(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            path = root / "capability-ministack.json"
            path.write_text(json.dumps(rows()), encoding="utf-8")
            with self.assertRaises(MODULE.CapabilityValidationError):
                MODULE.validate_and_write(path)
            self.assertFalse(path.exists())

    def test_accepts_closed_failed_reason_codes(self) -> None:
        report = rows(
            **{
                "PutRecord": {"status": "failed", "sanitizedReason": "http_forbidden"},
                "PutRecords": {"status": "failed", "sanitizedReason": "endpoint_failure"},
            }
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            path = self.write_report(Path(temp_dir), report)
            output = MODULE.validate_and_write(path)
            normalized = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(
                {"failed", "supported"},
                {row["status"] for row in normalized},
            )

    def test_accepts_observed_emulator_ignore_as_explicitly_unsupported(self) -> None:
        report = rows(
            **{
                "PutRecord": {
                    "status": "unsupported",
                    "sanitizedReason": "dry_run_ignored_write",
                },
                "GetRecords": {
                    "status": "unsupported",
                    "sanitizedReason": "dry_run_ignored_response",
                },
            }
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            path = self.write_report(Path(temp_dir), report)
            output = MODULE.validate_and_write(path)
            normalized = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("dry_run_ignored_write", normalized[0]["sanitizedReason"])
            self.assertEqual("dry_run_ignored_response", normalized[3]["sanitizedReason"])

    def test_step_summary_exposes_only_safe_capability_fields(self) -> None:
        report = rows(
            **{
                "PutRecord": {
                    "status": "unsupported",
                    "sanitizedReason": "dry_run_ignored_write",
                },
            }
        )

        summary = MODULE.render_step_summary(report)

        self.assertIn("- Backend: `floci`", summary)
        self.assertIn("- Version: `test-1.0`", summary)
        self.assertIn("| PutRecord | unsupported | dry_run_ignored_write |", summary)
        self.assertNotIn("streamToken", summary)
        self.assertNotIn("issue-620-0", summary)

    def test_missing_report_is_redacted_and_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "capability-floci.json"
            output = io.StringIO()
            with contextlib.redirect_stderr(output):
                self.assertEqual(1, MODULE.main([str(SCRIPT), str(path)]))
            self.assertEqual(
                "Kinesis DryRun capability validation failed (redacted).\n",
                output.getvalue(),
            )


if __name__ == "__main__":
    unittest.main()
