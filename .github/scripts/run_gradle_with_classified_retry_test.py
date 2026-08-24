"""Regression tests for the classified Gradle retry helper."""

from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("run-gradle-with-classified-retry.sh")
WORKFLOW = Path(__file__).parents[1] / "workflows" / "ci.yml"


class ClassifiedRetryTest(unittest.TestCase):
    def run_helper(self, fake_command: Path, *arguments: str) -> tuple[subprocess.CompletedProcess[str], Path]:
        temporary = Path(self.temp_dir.name)
        artifact_dir = temporary / "ci-retry-artifacts"
        summary = temporary / "step-summary.md"
        helper_options: tuple[str, ...] = ()
        command_arguments = arguments
        if "--fallback-emulator" in arguments:
            option_index = arguments.index("--fallback-emulator")
            helper_options = arguments[option_index : option_index + 2]
            command_arguments = arguments[option_index + 2 :]
        environment = os.environ.copy()
        environment.update(
            {
                "BLUETAPE4K_RETRY_ARTIFACT_DIR": str(artifact_dir),
                "BLUETAPE4K_RETRY_DELAY_SECONDS": "0",
                "BLUETAPE4K_RETRY_MAX_ATTEMPTS": "2",
                "GITHUB_STEP_SUMMARY": str(summary),
            }
        )
        result = subprocess.run(
            [str(SCRIPT), *helper_options, "--", str(fake_command), *command_arguments],
            cwd=temporary,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        return result, artifact_dir

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_fake_command(self, body: str) -> Path:
        command = Path(self.temp_dir.name) / "fake-gradle.sh"
        command.write_text("#!/usr/bin/env bash\nset -euo pipefail\n" + body, encoding="utf-8")
        command.chmod(0o755)
        return command

    def test_assertion_failure_stops_without_retry(self) -> None:
        command = self.write_fake_command(
            """
            count_file="$PWD/count"
            count=0
            [[ -f "$count_file" ]] && count=$(<"$count_file")
            count=$((count + 1))
            printf '%s' "$count" > "$count_file"
            printf '%s\n' 'Compilation failed: assertion expected value was 2'
            exit 1
            """
        )

        result, artifacts = self.run_helper(command, "test")

        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((Path(self.temp_dir.name) / "count").read_text(), "1")
        self.assertEqual(
            (artifacts / "classification.txt").read_text(encoding="utf-8").strip(),
            "non-infra-failure",
        )
        self.assertTrue((artifacts / "attempt-1.log").is_file())
        self.assertFalse((artifacts / "attempt-2.log").exists())

    def test_known_infra_failure_retries_and_preserves_first_failure(self) -> None:
        command = self.write_fake_command(
            """
            count_file="$PWD/count"
            count=0
            [[ -f "$count_file" ]] && count=$(<"$count_file")
            count=$((count + 1))
            printf '%s' "$count" > "$count_file"
            if [[ "$count" == '1' ]]; then
              mkdir -p build/test-results/test
              printf '%s\n' '<testsuite name="first-failure"/>' > build/test-results/test/result.xml
              printf '%s\n' 'ContainerLaunchException: Could not start container'
              exit 1
            fi
            printf '%s\n' 'tests passed'
            """
        )

        result, artifacts = self.run_helper(command, "test")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((Path(self.temp_dir.name) / "count").read_text(), "2")
        self.assertEqual(
            (artifacts / "classification.txt").read_text(encoding="utf-8").strip(),
            "infra-retry-pass",
        )
        self.assertTrue((artifacts / "attempt-1.log").is_file())
        self.assertTrue((artifacts / "attempt-2.log").is_file())
        self.assertTrue((artifacts / "first-failure/build/test-results/test/result.xml").is_file())

    def test_mapped_port_startup_race_retries(self) -> None:
        command = self.write_fake_command(
            """
            count_file="$PWD/count"
            count=0
            [[ -f "$count_file" ]] && count=$(<"$count_file")
            count=$((count + 1))
            printf '%s' "$count" > "$count_file"
            if [[ "$count" == '1' ]]; then
              printf '%s\n' 'java.lang.IllegalStateException: Mapped port can only be obtained after the container is started'
              exit 1
            fi
            printf '%s\n' 'tests passed'
            """
        )

        result, artifacts = self.run_helper(command, "test")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((Path(self.temp_dir.name) / "count").read_text(), "2")
        self.assertEqual(
            (artifacts / "classification.txt").read_text(encoding="utf-8").strip(),
            "infra-retry-pass",
        )
        self.assertTrue((artifacts / "attempt-2.log").is_file())

    def test_known_infra_failure_uses_localstack_fallback(self) -> None:
        command = self.write_fake_command(
            """
            count_file="$PWD/count"
            count=0
            [[ -f "$count_file" ]] && count=$(<"$count_file")
            count=$((count + 1))
            printf '%s' "$count" > "$count_file"
            if [[ "$*" == *'localstack'* ]]; then
              printf '%s\n' 'localstack tests passed'
              exit 0
            fi
            printf '%s\n' 'Connection refused while waiting for emulator'
            exit 1
            """
        )

        result, artifacts = self.run_helper(
            command,
            "--fallback-emulator",
            "localstack",
            "-Dbluetape4k.aws.emulator=floci",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((Path(self.temp_dir.name) / "count").read_text(), "2")
        self.assertEqual(
            (artifacts / "classification.txt").read_text(encoding="utf-8").strip(),
            "infra-fallback-pass",
        )
        self.assertTrue((artifacts / "fallback.log").is_file())

    def test_workflow_uses_classified_retry_for_all_test_entries(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")

        self.assertNotIn("for attempt in", workflow)
        self.assertNotIn("sleep 30", workflow)
        self.assertEqual(workflow.count("run-gradle-with-classified-retry.sh"), 11)
        self.assertEqual(workflow.count("BLUETAPE4K_RETRY_ARTIFACT_DIR"), 11)
        self.assertIn("BLUETAPE4K_RETRY_MAX_ATTEMPTS: '2'", workflow)
        self.assertIn("BLUETAPE4K_RETRY_DELAY_SECONDS: '15'", workflow)


if __name__ == "__main__":
    unittest.main()
