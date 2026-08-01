#!/usr/bin/env python3
"""Regression tests for Kover artifact layout normalization."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("validate_kover_artifacts.py")
SPEC = importlib.util.spec_from_file_location("validate_kover_artifacts", SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {SCRIPT}")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ValidateKoverArtifactsTest(unittest.TestCase):
    def test_normalizes_single_artifact_to_expected_name(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            report = root / "aws-java" / "build" / "reports" / "kover" / "report.xml"
            report.parent.mkdir(parents=True)
            report.write_text("<report />", encoding="utf-8")

            errors = MODULE.validate_artifacts(root, ["coverage-aws"])

            self.assertEqual({"missing": [], "empty": []}, errors)
            self.assertTrue(root.joinpath("coverage-aws", "aws-java", "build").is_dir())

    def test_accepts_named_directories_for_multiple_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            for artifact, module in (
                ("coverage-aws", "aws-java"),
                ("coverage-aws-ktor", "aws-ktor"),
            ):
                report = root / artifact / module / "build" / "reports" / "kover" / "report.xml"
                report.parent.mkdir(parents=True)
                report.write_text("<report />", encoding="utf-8")

            errors = MODULE.validate_artifacts(root, ["coverage-aws", "coverage-aws-ktor"])

            self.assertEqual({"missing": [], "empty": []}, errors)

    def test_reports_missing_or_empty_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            errors = MODULE.validate_artifacts(Path(temp_dir), ["coverage-aws"])

            self.assertEqual({"missing": ["coverage-aws"], "empty": []}, errors)


if __name__ == "__main__":
    unittest.main()
