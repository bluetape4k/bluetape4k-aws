#!/usr/bin/env python3
"""Remove Testcontainers credential properties before JUnit artifact upload."""

from __future__ import annotations

import glob
import os
import re
import sys
import tempfile
from argparse import ArgumentParser
from pathlib import Path


CREDENTIAL_PROPERTY = re.compile(
    r"(?i)testcontainers\.[^\r\n=]*(?:"
    r"aws-access-key|aws-secret-key|aws-session-token|"
    r"access-key|secret-key|session-token|credential|password"
    r")[^\r\n=]*=[^\r\n<\]]*"
)


def sanitize_file(path: Path) -> bool:
    original = path.read_text(encoding="utf-8")
    sanitized = CREDENTIAL_PROPERTY.sub("[redacted-testcontainers-credential-property]", original)
    if CREDENTIAL_PROPERTY.search(sanitized):
        raise RuntimeError("JUnit credential property sanitization failed")
    if sanitized == original:
        return False

    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(sanitized)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
    return True


def sanitize_paths(required_file: Path, patterns: list[str]) -> tuple[int, int]:
    if not required_file.is_file():
        raise FileNotFoundError("required Kinesis DryRun JUnit result is missing")
    paths = {required_file}
    paths.update(
        Path(match)
        for pattern in patterns
        for match in glob.glob(pattern, recursive=True)
        if Path(match).is_file()
    )
    changed = sum(sanitize_file(path) for path in sorted(paths))
    return len(paths), changed


def main(arguments: list[str]) -> int:
    parser = ArgumentParser()
    parser.add_argument("--require-file", required=True, type=Path)
    parser.add_argument("patterns", nargs="*")
    options = parser.parse_args(arguments)
    try:
        total, changed = sanitize_paths(options.require_file, options.patterns)
    except (FileNotFoundError, OSError, UnicodeError):
        print("Kinesis DryRun JUnit sanitization failed", file=sys.stderr)
        return 1
    print(f"Kinesis DryRun JUnit sanitization passed: files={total} sanitized={changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
