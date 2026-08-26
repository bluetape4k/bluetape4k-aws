#!/usr/bin/env python3
"""실제 AWS SNS 측정 preflight의 fail-closed 계약을 검증한다."""

import json
import unittest
from unittest.mock import patch

import sns_aws_measurement_preflight as preflight


def approved_environment() -> dict[str, str]:
    return {
        "BLUETAPE4K_AWS_SNS_APPROVAL": "approved",
        "BLUETAPE4K_AWS_SNS_ACCOUNT_ID": "123456789012",
        "AWS_REGION": "us-east-1",
        "AWS_PROFILE": "measurement-profile",
        "BLUETAPE4K_AWS_SNS_QUOTA_APPROVAL": "approved",
        "BLUETAPE4K_AWS_SNS_COST_LIMIT_USD": "12.50",
        "BLUETAPE4K_AWS_SNS_RETENTION_SECONDS": "60",
        "BLUETAPE4K_AWS_SNS_CONFIRM": "I_UNDERSTAND_AWS_COST_AND_DATA_REDACTION",
    }


class PreflightTest(unittest.TestCase):
    def test_missing_approval_fails_before_tool_or_aws_use(self) -> None:
        with patch.object(preflight.shutil, "which") as which:
            with self.assertRaises(preflight.PreflightError) as context:
                preflight.build_preflight({})

        which.assert_not_called()
        self.assertEqual(context.exception.code, "approval_required")

    def test_approved_environment_returns_redacted_contract(self) -> None:
        def tool(name: str) -> str:
            return f"/usr/bin/{name}"

        with patch.object(preflight.shutil, "which", side_effect=tool):
            result = preflight.build_preflight(approved_environment())

        self.assertEqual(result["backend"], "aws")
        self.assertEqual(result["credential_source"], "AWS_PROFILE")
        self.assertFalse(result["account_id_verified"])
        self.assertEqual(result["region"], "us-east-1")
        self.assertFalse(result["endpoint_override"])
        self.assertEqual(result["cost_limit_usd"], "12.50")
        self.assertEqual(result["retention_seconds"], 60)
        self.assertNotIn("123456789012", json.dumps(result))
        self.assertNotIn("measurement-profile", json.dumps(result))

    def test_endpoint_override_is_rejected(self) -> None:
        environment = approved_environment()
        environment["AWS_ENDPOINT_URL_SNS"] = "http://127.0.0.1:4566"

        with patch.object(preflight.shutil, "which", return_value="/usr/bin/tool"):
            with self.assertRaises(preflight.PreflightError) as context:
                preflight.build_preflight(environment)

        self.assertEqual(context.exception.code, "endpoint_override_forbidden")

    def test_invalid_cost_and_retention_are_rejected(self) -> None:
        for key, value in (
            ("BLUETAPE4K_AWS_SNS_COST_LIMIT_USD", "0"),
            ("BLUETAPE4K_AWS_SNS_COST_LIMIT_USD", "nan"),
            ("BLUETAPE4K_AWS_SNS_RETENTION_SECONDS", "59"),
            ("BLUETAPE4K_AWS_SNS_RETENTION_SECONDS", "not-a-number"),
        ):
            environment = approved_environment()
            environment[key] = value
            with patch.object(preflight.shutil, "which", return_value="/usr/bin/tool"):
                with self.assertRaises(preflight.PreflightError):
                    preflight.build_preflight(environment)

    def test_environment_credentials_are_rejected_when_profile_is_required(self) -> None:
        environment = approved_environment()
        environment["AWS_SECRET_ACCESS_KEY"] = "not-written-anywhere"

        with patch.object(preflight.shutil, "which", return_value="/usr/bin/tool"):
            with self.assertRaises(preflight.PreflightError) as context:
                preflight.build_preflight(environment)

        self.assertEqual(context.exception.code, "environment_credentials_forbidden")


if __name__ == "__main__":
    unittest.main()
