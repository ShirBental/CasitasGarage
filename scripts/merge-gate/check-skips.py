#!/usr/bin/env python3
"""Skip guard for the PRs below #10, whose CI has no scripts/ci/check-test-report.py yet.

Usage: check-skips.py target/surefire-reports MIN_TESTS

Fails when any test outside the opt-in Meross live tests is skipped, or fewer than MIN_TESTS ran.
"""
import glob
import sys
import xml.etree.ElementTree as ET

ALLOWED_SKIPS = {"home.casita.meross.MerossLiveCheckTest", "home.casita.meross.MerossLiveCommandTest"}

reports = glob.glob(f"{sys.argv[1]}/TEST-*.xml")
minimum = int(sys.argv[2])
total = skipped = 0
problems = []
for path in reports:
    for case in ET.parse(path).getroot().iter("testcase"):
        total += 1
        if case.find("skipped") is not None:
            skipped += 1
            if case.get("classname") not in ALLOWED_SKIPS:
                problems.append(f"skipped unexpectedly: {case.get('classname')}.{case.get('name')}")
        if case.find("failure") is not None or case.find("error") is not None:
            problems.append(f"failed: {case.get('classname')}.{case.get('name')}")
if total < minimum:
    problems.append(f"{total} tests ran, expected at least {minimum}")
print(f"{total} tests, {skipped} skipped")
for p in problems:
    print(f"NO-GO: {p}")
sys.exit(1 if problems else 0)
