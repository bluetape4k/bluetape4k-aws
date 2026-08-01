#!/usr/bin/env python3
"""Validate and normalize Kover coverage artifact layouts."""

from __future__ import annotations

import sys
from pathlib import Path


ARTIFACT_MODULES = {
    "coverage-aws": "aws-java",
    "coverage-aws-kotlin": "aws-kotlin",
    "coverage-aws-exposed": "aws-exposed",
    "coverage-aws-spring-boot": "aws-spring-boot",
    "coverage-aws-ktor": "aws-ktor",
}
REPORT_NAMES = ("report.xml", "reportJvm.xml")


def _candidate_directories(root: Path, artifact: str) -> tuple[Path, ...]:
    module = ARTIFACT_MODULES.get(artifact, artifact.removeprefix("coverage-"))
    candidates = [root / artifact.removeprefix("coverage-"), root / module]
    return tuple(dict.fromkeys(candidates))


def _has_report(directory: Path) -> bool:
    return any(path.is_file() and path.name in REPORT_NAMES for path in directory.rglob("*"))


def validate_artifacts(root: Path, artifacts: list[str]) -> dict[str, list[str]]:
    missing: list[str] = []
    empty: list[str] = []

    for artifact in artifacts:
        artifact_dir = root / artifact
        if not artifact_dir.is_dir():
            for candidate in _candidate_directories(root, artifact):
                if candidate.is_dir():
                    artifact_dir.mkdir(parents=True)
                    candidate.rename(artifact_dir / candidate.name)
                    break

        if not artifact_dir.is_dir():
            missing.append(artifact)
        elif not _has_report(artifact_dir):
            empty.append(artifact)

    return {"missing": missing, "empty": empty}


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(f"usage: {argv[0]} COVERAGE_ROOT ARTIFACT [ARTIFACT ...]", file=sys.stderr)
        return 2

    root = Path(argv[1])
    artifacts = argv[2:]
    errors = validate_artifacts(root, artifacts)
    if errors["missing"] or errors["empty"]:
        print("::error title=Coverage artifacts incomplete::Expected coverage artifacts are missing or empty.")
        if errors["missing"]:
            print("Missing artifacts:")
            print("\n".join(f"  - {artifact}" for artifact in errors["missing"]))
        if errors["empty"]:
            print("Empty artifacts:")
            print("\n".join(f"  - {artifact}" for artifact in errors["empty"]))
        print("Downloaded artifacts:")
        for directory in sorted(path for path in root.rglob("*") if path.is_dir()):
            print(directory)
        return 1

    print(f"Validated {len(artifacts)} expected coverage artifacts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
