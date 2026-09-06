#!/usr/bin/env python3
"""aws-exposed README 안정 버전과 CI 실행 계약을 검증한다."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = "io.github.bluetape4k.aws:bluetape4k-aws-exposed"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def require_match(pattern: str, text: str, label: str) -> re.Match[str]:
    match = re.search(pattern, text, flags=re.MULTILINE)
    require(match is not None, f"missing contract: {label}")
    return match


def stable_version(path: Path, label: str) -> str:
    text = path.read_text(encoding="utf-8")
    return require_match(rf"^{re.escape(label)}: `([^`]+)`$", text, str(path)).group(1)


def module_dependency_version(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    matches = re.findall(rf'implementation\("{re.escape(ARTIFACT)}:([^"$]+)"\)', text)
    require(
        len(matches) == 1, f"{path}: expected exactly one literal {ARTIFACT} dependency"
    )
    return matches[0]


def assert_consumer_examples() -> None:
    expected = f'implementation("{ARTIFACT}:${{bluetape4kAwsVersion}}")'
    for path in (
        ROOT / "aws-spring-boot/README.md",
        ROOT / "aws-spring-boot/README.ko.md",
        ROOT / "aws-ktor/README.md",
        ROOT / "aws-ktor/README.ko.md",
    ):
        require(
            expected in path.read_text(encoding="utf-8"), f"{path}: missing {expected}"
        )


def assert_bom_examples() -> None:
    expected = 'implementation(platform("io.github.bluetape4k.aws:bluetape4k-aws-bom:<version>"))'
    for path in (ROOT / "bom/README.md", ROOT / "bom/README.ko.md"):
        require(
            expected in path.read_text(encoding="utf-8"),
            f"{path}: missing BOM <version> example",
        )


def assert_ci_contract() -> None:
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    command = "python3 .github/scripts/aws_exposed_readme_version_contract_test.py"
    require(
        command in ci, "CI changes job must run aws-exposed README version contract"
    )
    require("- '**.md'" not in ci, "CI must not ignore every Markdown change")


def main() -> None:
    english_stable = stable_version(ROOT / "README.md", "Current stable version")
    korean_stable = stable_version(ROOT / "README.ko.md", "현재 안정 버전")
    require(
        english_stable == korean_stable, "root README stable versions differ by locale"
    )

    for path in (ROOT / "aws-exposed/README.md", ROOT / "aws-exposed/README.ko.md"):
        module_version = module_dependency_version(path)
        require(
            module_version == english_stable,
            f"{path}: dependency version {module_version} differs from stable {english_stable}",
        )

    assert_bom_examples()
    assert_consumer_examples()
    assert_ci_contract()
    print(f"aws-exposed README version contract passed: {english_stable}")


if __name__ == "__main__":
    main()
