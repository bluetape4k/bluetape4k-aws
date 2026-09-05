#!/usr/bin/env python3
"""Regression tests for Kinesis DryRun JUnit credential sanitization."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("sanitize_kinesis_dry_run_junit.py")
SPEC = importlib.util.spec_from_file_location("sanitize_kinesis_dry_run_junit", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class SanitizeKinesisDryRunJunitTest(unittest.TestCase):
    def test_removes_testcontainer_credential_properties(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "TEST-KinesisDryRun.xml"
            path.write_text(
                """<testsuite><system-out><![CDATA[
testcontainers.floci.host=localhost
testcontainers.floci.aws-access-key=test-access
testcontainers.floci.aws-secret-key=test-secret
testcontainers.floci.aws-session-token=test-token
]]></system-out></testsuite>""",
                encoding="utf-8",
            )

            changed = MODULE.sanitize_file(path)
            text = path.read_text(encoding="utf-8")

            self.assertTrue(changed)
            self.assertIn("testcontainers.floci.host=localhost", text)
            self.assertNotIn("test-access", text)
            self.assertNotIn("test-secret", text)
            self.assertNotIn("test-token", text)
            self.assertNotIn("aws-access-key", text)
            self.assertNotIn("aws-secret-key", text)
            self.assertNotIn("aws-session-token", text)

    def test_preserves_unrelated_junit_output(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "TEST-KinesisDryRun.xml"
            original = "<testsuite><system-out>safe output</system-out></testsuite>"
            path.write_text(original, encoding="utf-8")

            changed = MODULE.sanitize_file(path)

            self.assertFalse(changed)
            self.assertEqual(original, path.read_text(encoding="utf-8"))

    def test_handles_cdata_html_wrappers_and_indentation(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "wrapped.html"
            path.write_text(
                """<pre>testcontainers.floci.aws-access-key=wrapped-access</pre>
<![CDATA[testcontainers.floci.aws-secret-key=wrapped-secret]]>
    testcontainers.floci.aws-session-token=indented-token
""",
                encoding="utf-8",
            )

            self.assertTrue(MODULE.sanitize_file(path))
            text = path.read_text(encoding="utf-8")
            self.assertIn("<pre>[redacted-testcontainers-credential-property]</pre>", text)
            self.assertIn("<![CDATA[[redacted-testcontainers-credential-property]]]>", text)
            self.assertNotIn("wrapped-access", text)
            self.assertNotIn("wrapped-secret", text)
            self.assertNotIn("indented-token", text)

    def test_sanitizes_required_junit_and_retry_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            junit = root / "TEST-KinesisDryRun.xml"
            retry = root / "retry" / "attempt-1.log"
            retry.parent.mkdir()
            junit.write_text("testcontainers.floci.aws-access-key=test\n", encoding="utf-8")
            retry.write_text("testcontainers.floci.aws-secret-key=test\n", encoding="utf-8")

            total, changed = MODULE.sanitize_paths(junit, [str(root / "retry" / "**" / "*.log")])

            self.assertEqual((2, 2), (total, changed))
            self.assertNotIn("access-key", junit.read_text(encoding="utf-8"))
            self.assertNotIn("secret-key", retry.read_text(encoding="utf-8"))

    def test_fails_when_required_junit_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "missing.xml"

            with self.assertRaises(FileNotFoundError):
                MODULE.sanitize_paths(missing, [])


if __name__ == "__main__":
    unittest.main()
