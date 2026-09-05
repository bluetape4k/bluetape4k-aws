#!/usr/bin/env python3
"""Check the Issue #620 Kinesis DryRun documentation contract."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def section(text: str, heading: str) -> str:
    match = re.search(
        rf"(?ms)^{re.escape(heading)}\n(?P<body>.*?)(?=^###?\s|\Z)",
        text,
    )
    require(match is not None, f"missing section: {heading}")
    return match.group("body")


def kdoc_before(text: str, declaration: str) -> str:
    declaration_index = text.index(declaration)
    start = text.rfind("/**", 0, declaration_index)
    end = text.rfind("*/", 0, declaration_index)
    require(start >= 0 and end > start, f"missing KDoc before {declaration}")
    return text[start : end + 2]


def assert_readme(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    body = section(text, "### Kinesis (DSL)")
    require(body.count("```") >= 2 and body.count("```") % 2 == 0, f"{path}: unbalanced code fence")
    require(
        'putRecordRequestOf(streamName, data, partitionKey = "default")' not in body,
        f"{path}: obsolete positional helper call remains",
    )
    for token in (
        "putRecord",
        "putRecords",
        "getShardIterator",
        "getRecords",
        "putRecordRequestOf",
        "getShardIteratorRequestOf",
        "dryRun = true",
        "dryRun = false",
        "dryRun = null",
        "builder-last",
        "DryRun:false",
        "DryRunOperationException",
        "KinesisException",
        "coroutine cancellation",
        "payload",
        "credential",
        "endpoint",
        "client-side",
        "encryption",
        "network block",
        "Positional builder migration",
        "before:",
        "after:",
        "AWS Kinesis",
        "Floci `1.6.0`",
        "LocalStack `4`",
    ):
        require(token in body, f"{path}: missing Kinesis DryRun token: {token}")
    require("|" in body and "capability" in body.lower(), f"{path}: backend capability table is missing")
    require(
        "suspend fun validateKinesisDryRun(client: KinesisClient, existingShardIterator: String)" in body,
        f"{path}: runnable snippet must declare the Kinesis client",
    )
    require("AWS Kinesis" in body and "endpoint" in body, f"{path}: AWS-only execution boundary is missing")
    require("throw failed" in body, f"{path}: KinesisException must be rethrown")
    require(
        "error(" in body and ("unsupported" in body.lower() or "미지원" in body),
        f"{path}: normal response must fail closed",
    )
    require(
        "client.getRecords(existingShardIterator, dryRun = true)" in body,
        f"{path}: getRecords must use a caller-provided valid iterator",
    )
    require("non-DryRun `getShardIterator`" in body, f"{path}: iterator acquisition contract is missing")
    for operation in ("putRecord", "putRecords", "getShardIterator", "getRecords"):
        require(
            re.search(rf"validateAwsKinesisDryRun\s*\{{[\s\S]*?client\.{operation}\(", body) is not None,
            f"{path}: {operation} must have an independent DryRun exception boundary",
        )
    require(body.count("validateAwsKinesisDryRun {") == 4, f"{path}: expected four independent DryRun calls")
    return body


def assert_extension_kdocs() -> None:
    path = ROOT / "aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/KinesisClientExtensions.kt"
    text = path.read_text(encoding="utf-8")
    for name in ("putRecord", "putRecords", "getShardIterator", "getRecords"):
        kdoc = kdoc_before(text, f"suspend inline fun KinesisClient.{name}(")
        for token in ("dryRun", "DryRunOperationException", "coroutine cancellation", "builder"):
            require(token in kdoc, f"{path}: {name} KDoc missing {token}")
        require("@param dryRun" in kdoc, f"{path}: {name} KDoc missing @param dryRun")
        if name in ("putRecord", "putRecords"):
            for token in ("payload", "credential", "endpoint", "client-side", "encryption", "network block"):
                require(token in kdoc, f"{path}: {name} write warning missing {token}")


def assert_helper_kdocs() -> None:
    helpers = (
        (
            ROOT / "aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/PutRecord.kt",
            "inline fun putRecordRequestOf(",
        ),
        (
            ROOT / "aws-kotlin/src/main/kotlin/io/bluetape4k/aws/kotlin/kinesis/model/GetShardIterator.kt",
            "inline fun getShardIteratorRequestOf(",
        ),
    )
    for path, declaration in helpers:
        kdoc = kdoc_before(path.read_text(encoding="utf-8"), declaration)
        for token in ("dryRun", "DryRun", "false", "null", "builder", "서비스 호출", "서비스 예외"):
            require(token in kdoc, f"{path}: helper KDoc missing {token}")
        require("@param dryRun" in kdoc, f"{path}: helper KDoc missing @param dryRun")


def assert_changelog() -> None:
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    unreleased_match = re.search(r"(?ms)^## \[미출시]\n(?P<body>.*?)(?=^## [^#]|\Z)", text)
    require(unreleased_match is not None, "CHANGELOG [미출시] section is missing")
    added_match = re.search(r"(?ms)^### 추가\n(?P<body>.*?)(?=^### |\Z)", unreleased_match.group("body"))
    require(added_match is not None, "CHANGELOG [미출시] > 추가 section is missing")
    added = added_match.group("body")
    for token in ("#620", "dryRun", "named", "DryRunOperationException"):
        require(token in added, f"CHANGELOG [미출시] > 추가 missing {token}")


def main() -> None:
    english = assert_readme(ROOT / "aws-kotlin/README.md")
    korean = assert_readme(ROOT / "aws-kotlin/README.ko.md")
    require(english.count("```") == korean.count("```"), "English/Korean code-fence structure differs")
    require(
        sum(line.startswith("|") for line in english.splitlines())
        == sum(line.startswith("|") for line in korean.splitlines()),
        "English/Korean capability-table structure differs",
    )
    for token in ("putRecord", "putRecords", "getShardIterator", "getRecords"):
        require(english.count(token) == korean.count(token), f"English/Korean API structure differs for {token}")
    assert_extension_kdocs()
    assert_helper_kdocs()
    assert_changelog()
    print("Kinesis DryRun README/KDoc/CHANGELOG contract passed")


if __name__ == "__main__":
    main()
