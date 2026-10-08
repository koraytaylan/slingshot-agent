# SPDX-License-Identifier: MIT OR Apache-2.0
# Copyright 2026 Koray Taylan Davgana
"""Report aggregation must fail closed independently of Maven's exit status."""

import pathlib
import re
import shutil
import subprocess
import tempfile
import unittest

from quality_reports import main, require_pass


class ReportChecks(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = pathlib.Path(self.temporary.name)
        self.report = self.directory / "TEST-example.ExampleTest.xml"

    def write_report(self, attributes='', body=''):
        self.report.write_text(
            f'<testsuite tests="1" failures="0" errors="0" skipped="0" {attributes}>'
            f'{body}</testsuite>'
        )

    def test_complete_success(self):
        self.write_report(body='<testcase name="one"/>')
        require_pass(self.directory, ["ExampleTest"])

    def test_missing_report(self):
        with self.assertRaises(ValueError):
            require_pass(self.directory, ["ExampleTest"])

    def test_duplicate_report(self):
        self.write_report()
        (self.directory / "TEST-other.ExampleTest.xml").write_text(self.report.read_text())
        with self.assertRaises(ValueError):
            require_pass(self.directory, ["ExampleTest"])

    def test_empty_skipped_failed_and_error_suites(self):
        for field in ("tests", "failures", "errors", "skipped"):
            with self.subTest(field=field):
                self.write_report()
                value = '0' if field == 'tests' else '1'
                old = '1' if field == 'tests' else '0'
                self.report.write_text(self.report.read_text().replace(
                    f'{field}="{old}"', f'{field}="{value}"'))
                with self.assertRaises(ValueError):
                    require_pass(self.directory, ["ExampleTest"])

    def test_nested_success_markers_cannot_hide_failure(self):
        self.write_report(body='<testcase><failure failures="0" errors="0"/></testcase>')
        with self.assertRaises(ValueError):
            require_pass(self.directory, ["ExampleTest"])

    def test_malformed_report_refuses_at_command_boundary(self):
        self.report.write_text("not XML")
        self.assertEqual(1, main([str(self.directory), "ExampleTest"]))

    def test_required_attributes_cannot_be_omitted(self):
        self.report.write_text('<testsuite tests="1"/>')
        self.assertEqual(1, main([str(self.directory), "ExampleTest"]))


class GateExecutionChecks(unittest.TestCase):
    """Run the real shell gate with isolated, deterministic build stand-ins."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        repository = pathlib.Path(__file__).resolve().parent.parent
        for directory in ("scripts", "support", "policy"):
            (self.root / directory).mkdir()
        (self.root / "pom.xml").write_text(
            '<project><modules><module>development</module><module>interop</module></modules></project>')
        for relative in ("scripts/quality", "support/quality_reports.py", "policy/quality-gate.toml"):
            shutil.copy2(repository / relative, self.root / relative)
        # The isolated gate's self-check is a fixture, avoiding recursive tests.
        (self.root / "support/quality_reports_test.py").write_text(
            'import unittest\nclass Fixture(unittest.TestCase):\n'
            '    def test_fixture(self): self.assertTrue(True)\n')
        for name in ("verify_locked_dependency_cache", "verify_interop_images"):
            script = self.root / "scripts" / name
            script.write_text("#!/bin/sh\nexit 0\n")
            script.chmod(0o755)
        self.build = self.root / "mvnw"

    def run_gate(self, status=0, omit_report=False):
        script = (self.root / "scripts/quality").read_text()
        classes = [name for group in re.findall(r"^stage [\w-]+ decided (.*)$", script, re.M)
                   for name in group.split()]
        stale = self.root / "development/target/surefire-reports/TEST-stale.SourcePolicyTest.xml"
        stale.parent.mkdir(parents=True)
        stale.write_text('<testsuite tests="1" errors="0" failures="0" skipped="0"/>')
        self.build.write_text(
            '#!/usr/bin/env python3\n'
            'import pathlib, sys\n'
            f'root = pathlib.Path({str(self.root)!r})\n'
            'assert "--offline" in sys.argv and sys.argv[-1] == "verify"\n'
            f'assert not pathlib.Path({str(stale)!r}).exists(), "stale evidence survived"\n'
            '(root / "build-invocations").open("a").write("verify\\n")\n'
            f'classes = {classes!r}\n'
            f'classes = classes[1:] if {omit_report!r} else classes\n'
            'for module, names in [("development", classes), ("interop", ["PublicSlingTierTest"])]:\n'
            '    directory = root / module / "target/surefire-reports"\n'
            '    directory.mkdir(parents=True, exist_ok=True)\n'
            '    for name in names:\n'
            '        (directory / ("TEST-fixture." + name + ".xml")).write_text(\n'
            '            \'<testsuite tests="1" failures="0" errors="0" skipped="0"/>\')\n'
            f'sys.exit({status})\n'
        )
        self.build.chmod(0o755)
        return subprocess.run([str(self.root / "scripts/quality")], capture_output=True, text=True)

    def test_one_build_and_successful_fresh_reports(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("verify\n", (self.root / "build-invocations").read_text())
        self.assertIn("gate passed", result.stdout)
        self.assertIn("reactor-verification", (self.root / "target/quality-timings.tsv").read_text())

    def test_runner_failure_cannot_be_hidden_by_passing_reports(self):
        result = self.run_gate(status=7)
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("gate passed", result.stdout)
        self.assertTrue((self.root / "target/quality-timings.tsv").read_text().endswith("\t7\n"))

    def test_old_report_cannot_fill_missing_current_result(self):
        result = self.run_gate(omit_report=True)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("expected one fresh report", result.stderr)
        self.assertNotIn("gate passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
