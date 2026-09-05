#!/usr/bin/env python3
"""Validate and normalize the Kinesis DryRun capability report.

The report is deliberately a small, allow-listed JSON array.  The Kotlin test
suite owns the observations; this script is the CI boundary that prevents an
exception message, request material, or arbitrary diagnostic value from being
published as an artifact.
"""

from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path
from typing import Any


SCHEMA_VERSION = 1
OPERATIONS = (
    "PutRecord",
    "PutRecords",
    "GetShardIterator",
    "GetRecords",
)
ALLOWED_BACKENDS = {"floci", "localstack"}
ROW_FIELDS = {
    "schemaVersion",
    "backend",
    "backendVersion",
    "operation",
    "status",
    "sanitizedReason",
    "streamToken",
}
STATUSES = {"supported", "unsupported", "failed"}
SUPPORTED_REASONS = {"dry_run_accepted"}
UNSUPPORTED_REASONS = {
    "dry_run_ignored_response",
    "dry_run_ignored_write",
    "not_implemented",
    "unknown_dry_run_member",
}
FAILED_REASONS = {
    "access_denied",
    "assertion_failure",
    "cleanup_failure",
    "endpoint_failure",
    "http_forbidden",
    "network_failure",
    "normal_response",
    "timeout",
    "unexpected_failure",
}

MAX_REPORT_BYTES = 64 * 1024
MAX_VERSION_LENGTH = 64
MAX_STREAM_TOKEN_LENGTH = 96
SAFE_VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+:/-]{0,63}\Z")
SAFE_STREAM_TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}\Z")

# These markers are intentionally checked against the serialized input before
# normalization.  A report containing one is never allowed to reach the
# validated artifact, even if the marker is hidden in an unknown field.
SENSITIVE_MARKERS = (
    "access_key",
    "access-key",
    "accesskey",
    "authorization",
    "akia",
    "access key",
    "aws_access_key_id",
    "aws_secret_access_key",
    "aws_session_token",
    "bearer ",
    "credential",
    "header",
    "payload",
    "body",
    "request_body",
    "request-body",
    "secret_key",
    "secret-key",
    "session_token",
    "session-token",
    "session token",
    "userinfo",
)


class CapabilityValidationError(ValueError):
    """Raised for any report that cannot be safely published."""


def _unlink_quietly(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except OSError:
        # Validation still fails closed if cleanup itself is not possible.  Do
        # not expose the path or the operating-system diagnostic in CI logs.
        pass


def _contains_sensitive_marker(value: Any) -> bool:
    if isinstance(value, str):
        lowered = value.casefold()
        return any(marker in lowered for marker in SENSITIVE_MARKERS)
    if isinstance(value, dict):
        return any(
            _contains_sensitive_marker(key) or _contains_sensitive_marker(item)
            for key, item in value.items()
        )
    if isinstance(value, list):
        return any(_contains_sensitive_marker(item) for item in value)
    return False


def _expected_backend(report_path: Path) -> str:
    match = re.fullmatch(r"capability-([a-z0-9]+)\.json", report_path.name)
    if match is None or match.group(1) not in ALLOWED_BACKENDS:
        raise CapabilityValidationError("report filename is not an allowed backend")
    return match.group(1)


def _require_safe_string(value: Any, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise CapabilityValidationError(f"invalid {label}")
    return value


def _validate_rows(raw: Any, expected_backend: str) -> list[dict[str, Any]]:
    if not isinstance(raw, list) or len(raw) != len(OPERATIONS):
        raise CapabilityValidationError("report must contain exactly four rows")

    normalized: list[dict[str, Any]] = []
    seen_operations: set[str] = set()
    backend_version: str | None = None
    for row in raw:
        if not isinstance(row, dict) or set(row) != ROW_FIELDS:
            raise CapabilityValidationError("row fields do not match the allow-list")
        if isinstance(row["schemaVersion"], bool) or row["schemaVersion"] != SCHEMA_VERSION:
            raise CapabilityValidationError("unsupported schema version")

        backend = row["backend"]
        if backend != expected_backend or backend not in ALLOWED_BACKENDS:
            raise CapabilityValidationError("backend does not match the report path")
        backend_version = _require_safe_string(
            row["backendVersion"], SAFE_VERSION, "backend version"
        )
        if len(backend_version) > MAX_VERSION_LENGTH:
            raise CapabilityValidationError("backend version is too long")
        if normalized and backend_version != normalized[0]["backendVersion"]:
            raise CapabilityValidationError("backend version differs between rows")

        operation = row["operation"]
        if not isinstance(operation, str) or operation not in OPERATIONS or operation in seen_operations:
            raise CapabilityValidationError("operation is missing or duplicated")
        seen_operations.add(operation)

        status = row["status"]
        if not isinstance(status, str) or status not in STATUSES:
            raise CapabilityValidationError("status is outside the closed set")
        reason = row["sanitizedReason"]
        if not isinstance(reason, str):
            raise CapabilityValidationError("reason is outside the closed set")
        if status == "supported" and reason not in SUPPORTED_REASONS:
            raise CapabilityValidationError("supported reason is outside the closed set")
        if status == "unsupported" and reason not in UNSUPPORTED_REASONS:
            raise CapabilityValidationError("unsupported reason is outside the closed set")
        if status == "failed" and reason not in FAILED_REASONS:
            raise CapabilityValidationError("failed reason is outside the closed set")

        stream_token = _require_safe_string(
            row["streamToken"], SAFE_STREAM_TOKEN, "stream token"
        )
        if len(stream_token) > MAX_STREAM_TOKEN_LENGTH:
            raise CapabilityValidationError("stream token is too long")

        normalized.append(
            {
                "schemaVersion": SCHEMA_VERSION,
                "backend": backend,
                "backendVersion": backend_version,
                "operation": operation,
                "status": status,
                "sanitizedReason": reason,
                "streamToken": stream_token,
            }
        )

    if set(seen_operations) != set(OPERATIONS):
        raise CapabilityValidationError("report does not cover every operation")
    return sorted(normalized, key=lambda row: OPERATIONS.index(row["operation"]))


def validate_report(report_path: Path) -> tuple[Path, list[dict[str, Any]]]:
    """Validate *report_path* and return its normalized output path and rows."""

    expected_backend = _expected_backend(report_path)
    try:
        report_stat = report_path.stat()
    except OSError as exc:
        raise CapabilityValidationError("report is missing or unreadable") from exc
    if not report_path.is_file() or report_path.is_symlink():
        raise CapabilityValidationError("report is missing or is not a regular file")
    if report_stat.st_size > MAX_REPORT_BYTES:
        raise CapabilityValidationError("report is too large")
    try:
        raw_text = report_path.read_text(encoding="utf-8")
        raw = json.loads(raw_text)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise CapabilityValidationError("report is not valid JSON") from exc

    if _contains_sensitive_marker(raw):
        raise CapabilityValidationError("report contains a sensitive marker")
    rows = _validate_rows(raw, expected_backend)
    return report_path.with_name(f"capability-{expected_backend}.validated.json"), rows


def validate_and_write(report_path: Path) -> Path:
    """Fail closed, then write an allow-listed validated artifact on success."""

    validated_path = report_path.with_name(
        report_path.name.removesuffix(".json") + ".validated.json"
    )
    # A prior local run must not make a failed validation appear publishable.
    _unlink_quietly(validated_path)
    try:
        output_path, rows = validate_report(report_path)
        output_path.write_text(
            json.dumps(rows, ensure_ascii=True, indent=2) + "\n", encoding="utf-8"
        )
    except (CapabilityValidationError, OSError) as exc:
        _unlink_quietly(report_path)
        if isinstance(exc, CapabilityValidationError):
            raise
        raise CapabilityValidationError("validated report could not be written") from exc
    return output_path


def render_step_summary(rows: list[dict[str, Any]]) -> str:
    first = rows[0]
    lines = [
        "### Kinesis DryRun emulator capability",
        "",
        f"- Backend: `{first['backend']}`",
        f"- Version: `{first['backendVersion']}`",
        "",
        "| Operation | Status | Reason |",
        "| --- | --- | --- |",
    ]
    lines.extend(
        f"| {row['operation']} | {row['status']} | {row['sanitizedReason']} |"
        for row in rows
    )
    return "\n".join(lines) + "\n"


def append_step_summary(output_path: Path) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    rows = json.loads(output_path.read_text(encoding="utf-8"))
    with Path(summary_path).open("a", encoding="utf-8") as summary:
        summary.write(render_step_summary(rows))


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: validate_kinesis_dry_run_capability.py REPORT", file=sys.stderr)
        return 2

    report_path = Path(argv[1])
    try:
        output_path = validate_and_write(report_path)
        append_step_summary(output_path)
    except (CapabilityValidationError, OSError, json.JSONDecodeError):
        # Keep this line fixed and redacted.  Do not print exception text, path,
        # report contents, credentials, headers, or request payloads.
        print("Kinesis DryRun capability validation failed (redacted).", file=sys.stderr)
        return 1

    print(f"Kinesis DryRun capability validated: {output_path.name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
