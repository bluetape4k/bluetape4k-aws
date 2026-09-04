#!/usr/bin/env python3
"""Regression tests for the CodeQL workflow contract."""

from __future__ import annotations

import re
import shlex
import unittest
from pathlib import Path


WORKFLOW = Path(__file__).parents[1] / "workflows" / "code-quality.yml"


class CodeQualityWorkflowTest(unittest.TestCase):
    def test_java_kotlin_build_disables_gradle_build_cache(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        build_step = re.search(
            r"      - name: Build with Gradle\n(?P<body>(?:        .*\n)+)",
            workflow,
        )

        self.assertIsNotNone(build_step)
        assert build_step is not None
        self.assertIn("if: matrix.language == 'java-kotlin'", build_step.group("body"))
        run_line = re.search(
            r"^        run: (?P<command>.+)$",
            build_step.group("body"),
            re.MULTILINE,
        )

        self.assertIsNotNone(run_line)
        assert run_line is not None
        command = shlex.split(run_line.group("command"))
        self.assertEqual("./gradlew", command[0])
        self.assertIn("--no-build-cache", command)


if __name__ == "__main__":
    unittest.main()
