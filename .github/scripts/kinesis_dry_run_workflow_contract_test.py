#!/usr/bin/env python3
"""Check the CI/Nightly Kinesis DryRun capability gate contract."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RAW_REPORT = "aws-kotlin/build/reports/kinesis-dry-run/capability-floci.json"
VALIDATED_REPORT = "aws-kotlin/build/reports/kinesis-dry-run/capability-floci.validated.json"
VALIDATOR_ID = "validate-kinesis-dry-run-capability"
SANITIZER_ID = "sanitize-kinesis-dry-run-junit"


def job_block(workflow: str, job_name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(job_name)}:\n(?P<body>.*?)(?=^  [a-zA-Z0-9_-]+:\n|\Z)",
        workflow,
    )
    if match is None:
        raise AssertionError(f"missing workflow job: {job_name}")
    return match.group("body")


def assert_changes_job_runs_contracts(workflow: str) -> None:
    changes = job_block(workflow, "changes")
    for script in (
        "kinesis_dry_run_workflow_contract_test.py",
        "sanitize_kinesis_dry_run_junit_test.py",
        "validate_kinesis_dry_run_capability_test.py",
    ):
        if f"python3 .github/scripts/{script}" not in changes:
            raise AssertionError(f"changes job must run {script}")


def assert_capability_gate(workflow: str, workflow_name: str, timeout: int) -> None:
    body = job_block(workflow, "test-aws-kotlin")
    if not re.search(rf"^    timeout-minutes: {timeout}$", body, re.MULTILINE):
        raise AssertionError(f"{workflow_name}: unexpected aws-kotlin timeout")

    test_position = body.index("- name: Test aws-kotlin")
    validator_position = body.index("- name: Validate Kinesis DryRun capability")
    upload_position = body.index("- name: Upload Kinesis DryRun capability")
    if not test_position < validator_position < upload_position:
        raise AssertionError(f"{workflow_name}: capability gate must follow the test")
    if body.count(RAW_REPORT) != 1:
        raise AssertionError(f"{workflow_name}: raw report must appear only in validator")
    if body.count(VALIDATED_REPORT) != 1:
        raise AssertionError(f"{workflow_name}: validated report must appear only in upload")

    validator = re.search(
        rf"(?ms)^      - name: Validate Kinesis DryRun capability\n(?P<body>.*?)(?=^      - name: |\Z)",
        body,
    )
    if validator is None:
        raise AssertionError(f"{workflow_name}: capability validator step is missing")
    validator_body = validator.group("body")
    if "if: always()" not in validator_body:
        raise AssertionError(f"{workflow_name}: validator must run after test failure")
    if f"id: {VALIDATOR_ID}" not in validator_body:
        raise AssertionError(f"{workflow_name}: validator id is missing")
    if RAW_REPORT not in validator_body:
        raise AssertionError(f"{workflow_name}: validator must consume the raw report")

    upload = re.search(
        rf"(?ms)^      - name: Upload Kinesis DryRun capability\n(?P<body>.*?)(?=^      - name: |\Z)",
        body,
    )
    if upload is None:
        raise AssertionError(f"{workflow_name}: capability upload step is missing")
    upload_body = upload.group("body")
    if "success()" not in upload_body:
        raise AssertionError(f"{workflow_name}: upload must require the test step to succeed")
    if f"steps.{VALIDATOR_ID}.outcome == 'success'" not in upload_body:
        raise AssertionError(f"{workflow_name}: upload must require validator success")
    if VALIDATED_REPORT not in upload_body:
        raise AssertionError(f"{workflow_name}: upload must use the validated report")
    if RAW_REPORT in upload_body:
        raise AssertionError(f"{workflow_name}: upload must not use the raw report")
    if "name: kinesis-dry-run-capability" not in upload_body:
        raise AssertionError(f"{workflow_name}: capability artifact name is missing")
    if "if-no-files-found: error" not in upload_body:
        raise AssertionError(f"{workflow_name}: capability upload must fail when the artifact is missing")

    sanitizer_position = body.index("- name: Sanitize Kinesis DryRun JUnit output")
    test_results_position = body.index("- name: Upload test results")
    if not test_position < sanitizer_position < test_results_position:
        raise AssertionError(f"{workflow_name}: JUnit sanitization must precede test-result upload")
    sanitizer = re.search(
        r"(?ms)^      - name: Sanitize Kinesis DryRun JUnit output\n(?P<body>.*?)(?=^      - name: |\Z)",
        body,
    )
    if sanitizer is None:
        raise AssertionError(f"{workflow_name}: JUnit sanitizer step is missing")
    sanitizer_body = sanitizer.group("body")
    if "if: always()" not in sanitizer_body:
        raise AssertionError(f"{workflow_name}: JUnit sanitizer must run after test failure")
    if f"id: {SANITIZER_ID}" not in sanitizer_body:
        raise AssertionError(f"{workflow_name}: JUnit sanitizer id is missing")
    if "sanitize_kinesis_dry_run_junit.py" not in sanitizer_body:
        raise AssertionError(f"{workflow_name}: JUnit sanitizer command is missing")
    if "--require-file" not in sanitizer_body:
        raise AssertionError(f"{workflow_name}: JUnit sanitizer must require the primary result")
    if "'**/build/test-results/test/*.xml'" not in sanitizer_body:
        raise AssertionError(f"{workflow_name}: every uploaded main JUnit XML must be sanitized")
    if workflow_name == "CI":
        for suffix in ("*.xml", "*.html", "*.log"):
            if f"ci-retry-artifacts/aws-kotlin/**/{suffix}" not in sanitizer_body:
                raise AssertionError(f"{workflow_name}: retry {suffix} evidence bypasses sanitization")
    test_results_upload = re.search(
        r"(?ms)^      - name: Upload test results\n(?P<body>.*?)(?=^      - name: |\Z)",
        body,
    )
    if test_results_upload is None:
        raise AssertionError(f"{workflow_name}: test-result upload step is missing")
    if f"steps.{SANITIZER_ID}.outcome == 'success'" not in test_results_upload.group("body"):
        raise AssertionError(f"{workflow_name}: test-result upload must require sanitizer success")
    if workflow_name == "CI":
        test_results_body = test_results_upload.group("body")
        if "ci-retry-artifacts/aws-kotlin/**\n" in test_results_body:
            raise AssertionError(f"{workflow_name}: retry upload must exclude binary and unknown extensions")
        for path in (
            "ci-retry-artifacts/aws-kotlin/**/*.xml",
            "ci-retry-artifacts/aws-kotlin/**/*.html",
            "ci-retry-artifacts/aws-kotlin/**/*.log",
            "ci-retry-artifacts/aws-kotlin/classification.txt",
        ):
            if path not in test_results_body:
                raise AssertionError(f"{workflow_name}: missing sanitized retry upload path {path}")


def main() -> None:
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    nightly = (ROOT / ".github/workflows/nightly-tests.yml").read_text(encoding="utf-8")
    assert_changes_job_runs_contracts(ci)
    assert_capability_gate(ci, "CI", 30)
    assert_capability_gate(nightly, "Nightly", 75)
    print("Kinesis DryRun workflow contract passed")


if __name__ == "__main__":
    main()
