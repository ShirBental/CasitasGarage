#!/usr/bin/env python3
"""CI guard over surefire's XML reports: no test may be skipped unless it is on the allowlist below, and the
Firestore tests must have actually run (a missing emulator would otherwise skip them, or count them as 0 run).

Usage: check-test-report.py target/surefire-reports
"""
import glob
import sys
import xml.etree.ElementTree as ET

# Opt-in tests against the real Meross account and opener: skipped unless a person asks for them with -D
# properties. CI never does (simulation only).
ALLOWED_SKIPS = {"home.casita.meross.MerossLiveCheckTest", "home.casita.meross.MerossLiveCommandTest"}
# Each must have run at least this many tests, none skipped.
MUST_RUN = {"home.casita.security.store.FirestoreSecurityStoreTest": 60, "home.casita.security.access.FirestoreAccessFlowTest": 50}

reports = glob.glob(f"{sys.argv[1]}/TEST-*.xml")
if not reports:
    sys.exit("::error::no surefire reports found")
problems, ran, total, skipped = [], {}, 0, 0
for path in reports:
    suite = ET.parse(path).getroot()
    for case in suite.iter("testcase"):
        cls = case.get("classname")
        total += 1
        ran.setdefault(cls, 0)
        if case.find("skipped") is not None:
            skipped += 1
            if cls not in ALLOWED_SKIPS:
                problems.append(f"skipped unexpectedly: {cls}.{case.get('name')}")
        else:
            ran[cls] += 1
for cls, minimum in MUST_RUN.items():
    if ran.get(cls, 0) < minimum:
        problems.append(f"{cls}: {ran.get(cls, 0)} tests ran, expected at least {minimum} (is the Firestore emulator running?)")
print(f"{total} tests in {len(reports)} reports, {skipped} skipped (allowed: {', '.join(sorted(ALLOWED_SKIPS))})")
for p in problems:
    print(f"::error::{p}")
sys.exit(1 if problems else 0)
