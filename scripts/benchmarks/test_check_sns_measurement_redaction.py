#!/usr/bin/env python3
"""SNS 측정 산출물 redaction 검사기의 회귀 테스트."""

import tempfile
import unittest
from pathlib import Path

from check_sns_measurement_redaction import scan


class RedactionTest(unittest.TestCase):
    def test_safe_artifacts_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "environment.json").write_text(
                '{"backend":"aws","region":"us-east-1","credential_source":"AWS_PROFILE"}\n'
            )
            (root / "summary.json").write_text(
                '{"throughput_messages_per_second":123.0,"retention_seconds":60}\n'
            )
            self.assertEqual(scan(root), [])

    def test_forbidden_credential_and_payload_fields_fail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "unsafe.json").write_text(
                '{"accessKeyId":"AKIAIOSFODNN7EXAMPLE",'
                '"message":"private payload","topicArn":"arn:aws:sns:us-east-1:123456789012:topic",'
                '"token":"session-secret"}\n'
            )
            findings = scan(root)

        codes = {finding.code for finding in findings}
        self.assertIn("access_key", codes)
        self.assertIn("message_field", codes)
        self.assertIn("topic_arn", codes)
        self.assertIn("token_field", codes)

    def test_jfr_binary_is_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "heap-profile.jfr").write_bytes(b'\x00"message":"payload"AKIAIOSFODNN7EXAMPLE')
            self.assertEqual(scan(root), [])


if __name__ == "__main__":
    unittest.main()
