#!/usr/bin/env python3
"""SNS 실제 측정 텍스트 산출물에 credential·payload가 남지 않았는지 검사한다."""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Finding:
    path: str
    code: str


PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("access_key", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("secret_key", re.compile(r"(?i)(?:aws[_-]?secret[_-]?access[_-]?key|secretAccessKey)\s*[:=]")),
    ("session_token", re.compile(r"(?i)(?:aws[_-]?session[_-]?token|sessionToken)\s*[:=]")),
    ("security_token", re.compile(r"(?i)x-amz-security-token|securityToken")),
    ("authorization", re.compile(r"(?i)\b(?:authorization|x-amz-signature|x-amz-credential|credential=)")),
    ("message_field", re.compile(r'(?i)["\']message["\']\s*:')),
    ("credential_field", re.compile(r'(?i)["\']credential["\']\s*:')),
    ("token_field", re.compile(r'(?i)["\']token["\']\s*:')),
    ("topic_arn", re.compile(r"\barn:aws:sns:[^\s\"']+")),
    ("topic_field", re.compile(r'(?i)["\']topicArn["\']\s*:')),
    ("payload_assignment", re.compile(r'''(?i)["']?(?:payload|fullMessage)["']?\s*[:=]\s*["']''')),
)


def scan(root: Path) -> list[Finding]:
    """텍스트 파일을 검사하고 내용 대신 파일·코드만 반환한다."""

    findings: list[Finding] = []
    if not root.is_dir():
        return [Finding(str(root), "result_directory_missing")]
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() == ".jfr":
            continue
        try:
            raw = path.read_bytes()
        except OSError:
            findings.append(Finding(str(path.relative_to(root)), "read_failed"))
            continue
        if b"\x00" in raw:
            # JFR is explicitly skipped; other binary files are not text evidence.
            continue
        content = raw.decode("utf-8", errors="replace")
        relative = str(path.relative_to(root))
        for code, pattern in PATTERNS:
            if pattern.search(content):
                findings.append(Finding(relative, code))
    return findings


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args(argv)
    findings = scan(args.root)
    if findings:
        for finding in findings:
            print(f"redaction failed: {finding.path} [{finding.code}]", file=sys.stderr)
        return 1
    print(f"redaction PASS: {args.root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
