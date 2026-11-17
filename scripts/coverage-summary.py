#!/usr/bin/env python3
"""Prints a Markdown table of JaCoCo line/branch coverage per Maven module (for the CI job summary).

Usage: python3 scripts/coverage-summary.py >> "$GITHUB_STEP_SUMMARY"
"""
import glob
import os
import xml.etree.ElementTree as ET


def counter(root, kind):
    for c in root.findall("counter"):
        if c.get("type") == kind:
            missed, covered = int(c.get("missed")), int(c.get("covered"))
            total = missed + covered
            return (100.0 * covered / total) if total else None
    return None


def fmt(value):
    return "n/a" if value is None else f"{value:.1f}%"


rows = []
for path in sorted(glob.glob("*/target/site/jacoco/jacoco.xml")):
    module = path.split(os.sep)[0]
    root = ET.parse(path).getroot()
    rows.append((module, counter(root, "LINE"), counter(root, "BRANCH")))

print("### Test coverage (JaCoCo)\n")
print("| Module | Lines | Branches |")
print("|---|---|---|")
for module, lines, branches in rows:
    print(f"| {module} | {fmt(lines)} | {fmt(branches)} |")
