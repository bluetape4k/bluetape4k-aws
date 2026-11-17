from __future__ import annotations

import os
import re
import textwrap
import unittest
from pathlib import Path


def repository_root() -> Path:
    current = Path.cwd().resolve()
    for candidate in (current, *current.parents):
        if (candidate / "settings.gradle.kts").is_file():
            return candidate
    raise AssertionError(f"저장소 루트를 찾을 수 없습니다: {current}")


class AwsDocumentationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.root = repository_root()
        configured_manual_root = os.environ.get("BLUETAPE4K_MANUAL_ROOT")
        cls.manual_root = Path(configured_manual_root) if configured_manual_root else cls.root / "docs/manual"

    def read(self, relative_path: str) -> str:
        if relative_path.startswith("docs/manual/"):
            if not self.manual_root.is_dir():
                self.skipTest("central manual checkout is not available")
            path = self.manual_root / relative_path.removeprefix("docs/manual/")
        else:
            path = self.root / relative_path
        return path.read_text(encoding="utf-8")

    def test_aws_config_data_readmes_link_to_the_manual_source_of_truth(self) -> None:
        readme = self.read("aws-spring-boot/README.md")
        korean_readme = self.read("aws-spring-boot/README.ko.md")
        self.assertIn(
            "https://bluetape4k.github.io/manual/bluetape4k-aws/0.5/modules/"
            "bluetape4k-aws-spring-boot/runtime-operations/",
            readme,
        )
        self.assertIn(
            "https://bluetape4k.github.io/ko/manual/bluetape4k-aws/0.5/modules/"
            "bluetape4k-aws-spring-boot/runtime-operations/",
            korean_readme,
        )

    def test_config_data_manual_locales_keep_snippets_and_headings_aligned(self) -> None:
        english = self.read("docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        korean = self.read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        required_tokens = (
            "spring.config.import", "aws-s3:", "aws-parameterstore:", "aws-secretsmanager:", "aws-app-config:",
            "optional:", "Floci", "LocalStack", "required-minimum-poll-interval", "Spring Cloud Context",
        )
        for token in required_tokens:
            self.assertIn(token, english)
            self.assertIn(token, korean)
        self.assertEqual(
            sum(line.startswith("## ") for line in english.splitlines()),
            sum(line.startswith("## ") for line in korean.splitlines()),
        )

    def test_sqs_manual_examples_match_the_tested_customization_source(self) -> None:
        source = self.tagged_source(
            self.read(
                "aws-spring-boot/src/test/kotlin/io/bluetape4k/aws/spring/sqs/"
                "SqsObservationCustomizationExampleTest.kt"
            )
        )
        for locale in ("en", "ko"):
            manual = self.read(f"docs/manual/{locale}/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md")
            self.assertEqual(
                self.normalize_kotlin_snippet(source),
                self.normalize_kotlin_snippet(self.fenced_example(manual)),
            )

    def test_sqs_manual_locales_keep_the_observation_contract_aligned(self) -> None:
        english_storage = self.read("docs/manual/en/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md")
        korean_storage = self.read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/storage-and-messaging.md")
        english_runtime = self.read("docs/manual/en/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        korean_runtime = self.read("docs/manual/ko/modules/bluetape4k-aws-spring-boot/runtime-operations.md")
        required_tokens = (
            "bluetape4k.aws.sqs.observation.enabled", "bluetape4k.aws.sqs.receive", "bluetape4k.aws.sqs.process",
            "bluetape4k.aws.sqs.acknowledgement", "SqsObservationContextCustomizer", "SqsObservationConvention",
            "SqsObservationFactory", "ObservationRegistry.NOOP", "BT4K-SQS-OBS-101", "BT4K-SQS-OBS-201",
            "BT4K-SQS-OBS-202", "reason=heartbeat_telemetry_setup", "STOPPING_RECEIVE -> DRAINING -> STOPPED",
            "FlociServer.Launcher.floci", "#453",
        )
        for token in required_tokens:
            english = english_runtime if token.startswith(("BT4K-", "STOPPING")) or token == "#453" else english_storage
            korean = korean_runtime if token.startswith(("BT4K-", "STOPPING")) or token == "#453" else korean_storage
            self.assertIn(token, english)
            self.assertIn(token, korean)
        self.assertEqual(
            sum(line.startswith("## ") for line in english_storage.splitlines()),
            sum(line.startswith("## ") for line in korean_storage.splitlines()),
        )
        self.assertEqual(
            sum(line.startswith("## ") for line in english_runtime.splitlines()),
            sum(line.startswith("## ") for line in korean_runtime.splitlines()),
        )

    @staticmethod
    def tagged_source(source: str) -> str:
        start = "// tag::sqs-observation-customization[]"
        end = "// end::sqs-observation-customization[]"
        if start not in source or end not in source:
            raise AssertionError("SQS customization source markers are missing")
        return textwrap.dedent(source.split(start, 1)[1].split(end, 1)[0]).strip()

    @staticmethod
    def fenced_example(manual: str) -> str:
        start = "<!-- sqs-observation-customization:start -->"
        end = "<!-- sqs-observation-customization:end -->"
        if start not in manual or end not in manual:
            raise AssertionError("SQS manual customization markers are missing")
        marked = manual.split(start, 1)[1].split(end, 1)[0]
        if "```kotlin" not in marked:
            raise AssertionError("SQS manual Kotlin fence is missing")
        return textwrap.dedent(marked.split("```kotlin", 1)[1].split("```", 1)[0]).strip()

    @staticmethod
    def normalize_kotlin_snippet(source: str) -> str:
        """Ignore formatting whitespace around Kotlin colons, not string contents."""
        normalized: list[str] = []
        index = 0
        state = "code"
        while index < len(source):
            char = source[index]
            next_char = source[index + 1] if index + 1 < len(source) else ""

            if state == "code":
                if char == '"' and source[index:index + 3] == '"""':
                    normalized.extend(('"', '"', '"'))
                    index += 3
                    state = "triple-string"
                    continue
                if char == '"':
                    normalized.append(char)
                    index += 1
                    state = "string"
                    continue
                if char == "'":
                    normalized.append(char)
                    index += 1
                    state = "char"
                    continue
                if char == "/" and next_char == "/":
                    normalized.extend((char, next_char))
                    index += 2
                    state = "line-comment"
                    continue
                if char == "/" and next_char == "*":
                    normalized.extend((char, next_char))
                    index += 2
                    state = "block-comment"
                    continue
                if char == ":":
                    while normalized and normalized[-1].isspace():
                        normalized.pop()
                    normalized.append(char)
                    index += 1
                    while index < len(source) and source[index].isspace():
                        index += 1
                    continue
                normalized.append(char)
                index += 1
                continue

            normalized.append(char)
            index += 1
            if state == "string" and char == "\\":
                if index < len(source):
                    normalized.append(source[index])
                    index += 1
            elif state == "string" and char == '"':
                state = "code"
            elif state == "char" and char == "\\":
                if index < len(source):
                    normalized.append(source[index])
                    index += 1
            elif state == "char" and char == "'":
                state = "code"
            elif state == "triple-string" and source[index - 1:index + 2] == '"""':
                normalized.extend((source[index], source[index + 1]))
                index += 2
                state = "code"
            elif state == "line-comment" and char == "\n":
                state = "code"
            elif state == "block-comment" and char == "*" and next_char == "/":
                normalized.append(next_char)
                index += 1
                state = "code"

        return "".join(normalized)


if __name__ == "__main__":
    unittest.main()
