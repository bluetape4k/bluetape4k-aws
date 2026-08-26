#!/usr/bin/env python3
"""실제 AWS SNS 측정 전에 승인·도구·endpoint 경계를 검증한다.

이 모듈은 AWS API를 호출하지 않는다. 성공 시에도 자격증명 프로필과 계정 ID를
출력하지 않으며, 실제 caller identity 확인은 실행 wrapper가 별도로 수행한다.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Callable, Mapping


class PreflightError(ValueError):
    """사용자 입력 또는 로컬 실행 조건이 안전한 측정 계약을 충족하지 못했다."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


APPROVAL_KEY = "BLUETAPE4K_AWS_SNS_APPROVAL"
ACCOUNT_KEY = "BLUETAPE4K_AWS_SNS_ACCOUNT_ID"
REGION_KEY = "AWS_REGION"
PROFILE_KEY = "AWS_PROFILE"
QUOTA_APPROVAL_KEY = "BLUETAPE4K_AWS_SNS_QUOTA_APPROVAL"
COST_KEY = "BLUETAPE4K_AWS_SNS_COST_LIMIT_USD"
RETENTION_KEY = "BLUETAPE4K_AWS_SNS_RETENTION_SECONDS"
CONFIRM_KEY = "BLUETAPE4K_AWS_SNS_CONFIRM"
ENDPOINT_KEYS = ("AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_SNS")
CREDENTIAL_KEYS = (
    "AWS_ACCESS_KEY_ID",
    "AWS_SECRET_ACCESS_KEY",
    "AWS_SESSION_TOKEN",
    "AWS_SECURITY_TOKEN",
)
REQUIRED_TOOLS = ("aws", "java", "jcmd", "jfr")
CONFIRMATION = "I_UNDERSTAND_AWS_COST_AND_DATA_REDACTION"
REGION_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)+-\d+$")
ACCOUNT_PATTERN = re.compile(r"^\d{12}$")

MATRIX = {
    "entryCount": [1, 10, 11, 20, 21, 100],
    "maxInFlightBatches": [1, 2, 4],
    "scenario": ["success", "transport"],
}


def _required(env: Mapping[str, str], key: str) -> str:
    value = env.get(key, "").strip()
    if not value:
        raise PreflightError("approval_required", f"{key} must be set")
    return value


def _approved(env: Mapping[str, str], key: str) -> None:
    if env.get(key, "").strip().lower() != "approved":
        raise PreflightError("approval_required", f"{key}=approved is required")


def _cost_limit(value: str) -> str:
    try:
        decimal = Decimal(value)
    except InvalidOperation as error:
        raise PreflightError("invalid_cost_limit", "cost limit must be a positive decimal") from error
    if not decimal.is_finite() or decimal <= 0:
        raise PreflightError("invalid_cost_limit", "cost limit must be a positive decimal")
    # Keep the user-approved spelling without exposing any other environment value.
    return format(decimal, "f")


def _retention(value: str) -> int:
    try:
        seconds = int(value)
    except ValueError as error:
        raise PreflightError("invalid_retention", "retention must be an integer number of seconds") from error
    if seconds < 60:
        raise PreflightError("invalid_retention", "retention must be at least 60 seconds")
    return seconds


def _check_tools(which: Callable[[str], str | None]) -> None:
    missing = [tool for tool in REQUIRED_TOOLS if which(tool) is None]
    if missing:
        raise PreflightError("required_tool_missing", f"required tools are unavailable: {','.join(missing)}")


def build_preflight(
    env: Mapping[str, str] | None = None,
    *,
    which: Callable[[str], str | None] | None = None,
) -> dict[str, object]:
    """환경을 redacted JSON 계약으로 검증한다. 이 함수는 네트워크를 사용하지 않는다."""

    values = os.environ if env is None else env
    which = shutil.which if which is None else which
    _approved(values, APPROVAL_KEY)
    _approved(values, QUOTA_APPROVAL_KEY)

    account_id = _required(values, ACCOUNT_KEY)
    if not ACCOUNT_PATTERN.fullmatch(account_id):
        raise PreflightError("invalid_account_id", "account ID must contain exactly 12 digits")

    region = _required(values, REGION_KEY)
    if not REGION_PATTERN.fullmatch(region):
        raise PreflightError("invalid_region", "AWS_REGION is not a valid explicit region")

    profile = _required(values, PROFILE_KEY)
    del profile  # The profile is intentionally never returned or logged.
    for key in ENDPOINT_KEYS:
        if values.get(key, "").strip():
            raise PreflightError("endpoint_override_forbidden", f"{key} must be empty for real AWS measurement")
    for key in CREDENTIAL_KEYS:
        if values.get(key, "").strip():
            raise PreflightError(
                "environment_credentials_forbidden",
                f"{key} must be empty; use AWS_PROFILE for the approved credential source",
            )

    cost_limit = _cost_limit(_required(values, COST_KEY))
    retention_seconds = _retention(_required(values, RETENTION_KEY))
    if values.get(CONFIRM_KEY, "").strip() != CONFIRMATION:
        raise PreflightError("confirmation_required", f"{CONFIRM_KEY} must contain the exact confirmation token")

    _check_tools(which)
    return {
        "schema_version": 1,
        "backend": "aws",
        "credential_source": "AWS_PROFILE",
        "account_id_verified": False,
        "region": region,
        "endpoint_override": False,
        "cost_limit_usd": cost_limit,
        "retention_seconds": retention_seconds,
        "matrix": MATRIX,
        "warmups": 1,
        "repetitions": 3,
        "identity_check": "aws sts get-caller-identity (account value omitted)",
        "redaction": {
            "credentials": "omitted",
            "topic_arns": "omitted",
            "messages": "omitted",
            "jfr": "allocation-and-retention events only",
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="검증된 redacted JSON을 저장할 경로")
    args = parser.parse_args(argv)
    try:
        result = build_preflight()
    except PreflightError as error:
        print(f"preflight failed [{error.code}]: {error}", file=sys.stderr)
        return 2

    serialized = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        temporary.write_text(serialized)
        json.loads(temporary.read_text())
        temporary.replace(args.output)
    else:
        sys.stdout.write(serialized)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
