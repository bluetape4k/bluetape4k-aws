#!/usr/bin/env python3
"""bluetape4k-dependencies pin과 관련 CI gate의 source contract를 검증한다."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TARGET_REF = "9698c9d66bea6fcba373143ee8fa5bfbd9812d4b"


def require_match(pattern: str, text: str, label: str) -> re.Match[str]:
    match = re.search(pattern, text, flags=re.MULTILINE | re.DOTALL)
    if match is None:
        raise AssertionError(f"missing contract: {label}")
    return match


def job_block(workflow: str, job_name: str) -> str:
    pattern = rf"(?ms)^  {re.escape(job_name)}:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)"
    return require_match(pattern, workflow, f"job {job_name}").group("body")


def filter_block(changes_job: str, filter_name: str) -> str:
    pattern = rf"(?ms)^            {re.escape(filter_name)}:\n(?P<body>(?:^              .+\n)+)"
    return require_match(pattern, changes_job, f"filter {filter_name}").group("body")


def timeout_minutes(workflow: str, job_name: str) -> int:
    body = job_block(workflow, job_name)
    value = require_match(r"^    timeout-minutes: (\d+)$", body, f"{job_name} timeout").group(1)
    return int(value)


def main() -> None:
    settings = (ROOT / "settings.gradle.kts").read_text()
    ci = (ROOT / ".github/workflows/ci.yml").read_text()
    nightly = (ROOT / ".github/workflows/nightly-tests.yml").read_text()

    settings_ref = require_match(
        r'\.orElse\("([0-9a-f]{40})"\)', settings, "settings default catalog ref"
    ).group(1)
    ci_ref = require_match(
        r"^  BLUETAPE4K_DEPENDENCIES_CATALOG_REF: '([0-9a-f]{40})'$",
        ci,
        "CI catalog ref",
    ).group(1)
    assert settings_ref == ci_ref == TARGET_REF, (
        f"catalog pin mismatch: settings={settings_ref}, ci={ci_ref}, target={TARGET_REF}"
    )

    changes = job_block(ci, "changes")
    assert "python3 .github/scripts/catalog_pin_contract_test.py" in changes, (
        "changes job must run catalog_pin_contract_test.py"
    )
    compatibility = filter_block(changes, "compatibility")
    for required_path in (
        "'settings.gradle.kts'",
        "'aws-kotlin/src/main/**'",
        "'src/abi-fixtures/**'",
        "'build.gradle.kts'",
    ):
        assert required_path in compatibility, f"compatibility filter missing {required_path}"

    ci_status = job_block(ci, "ci-status")
    needs = require_match(r"(?ms)^    needs:\n(?P<body>(?:^      - .+\n)+)", ci_status, "ci-status needs").group("body")
    assert "- compatibility" in needs, "ci-status must depend on compatibility"
    assert "needs.changes.outputs.compatibility" in ci_status, (
        "ci-status must evaluate the compatibility filter result"
    )
    assert "needs.compatibility.result" in ci_status, (
        "ci-status must evaluate the compatibility job result"
    )
    assert "skipped jobs treated as success" not in ci_status, (
        "ci-status must not accept every skipped job"
    )

    assert timeout_minutes(ci, "test-aws-kotlin") == 30, (
        "PR CI aws-kotlin timeout must be 30 minutes"
    )
    assert timeout_minutes(nightly, "test-aws-kotlin") == 75, (
        "Full Nightly aws-kotlin timeout must be 75 minutes"
    )

    print("catalog pin contract passed")


if __name__ == "__main__":
    main()
