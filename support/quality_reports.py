#!/usr/bin/env python3
# SPDX-License-Identifier: MIT OR Apache-2.0
# Copyright 2026 Koray Taylan Davgana
"""Validate fresh Surefire reports after the reactor has succeeded."""

import pathlib
import sys
import xml.etree.ElementTree as tree


def require_pass(directory, classes):
    """Reject absent, ambiguous, empty, skipped, malformed or failing suites."""
    for name in classes:
        reports = list(directory.glob(f"TEST-*.{name}.xml"))
        if len(reports) != 1:
            raise ValueError(f"{name}: expected one fresh report in {directory}, found {len(reports)}")
        report = reports[0]
        root = tree.parse(report).getroot()
        if root.tag != "testsuite" or int(root.attrib["tests"]) <= 0:
            raise ValueError(f"{name}: no tests executed in {report}")
        if any(int(root.attrib[field]) != 0 for field in ("failures", "errors", "skipped")):
            raise ValueError(f"{name}: failing or skipped tests in {report}")
        if any(root.findall(f".//{tag}") for tag in ("failure", "error", "skipped")):
            raise ValueError(f"{name}: failing or skipped test case in {report}")


def main(arguments):
    """Report a useful refusal and preserve failure at the shell boundary."""
    if len(arguments) < 2:
        print("usage: quality_reports.py REPORT_DIRECTORY CLASS...", file=sys.stderr)
        return 2
    try:
        require_pass(pathlib.Path(arguments[0]), arguments[1:])
    except (OSError, ValueError, KeyError, tree.ParseError) as failure:
        print(f"quality reports: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
