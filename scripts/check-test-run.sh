#!/bin/bash
# Did that test run actually run the tests?
#
# Usage: scripts/check-test-run.sh <sbt-output-log> [expected-total]
#   exit 0 = the log shows a FULL run: executed count == expected total
#   exit 1 = the log shows a PARTIAL run, or a count that disagrees
#   exit 2 = could not run: no log, no summary in it, or no expected total
#
# WHAT THIS GUARDS, AND WHY THE EXIT CODE CANNOT.
# sbt 2 caches test results machine-wide, and `test` resolves to testQuick
# semantics -- so it re-runs only what it believes changed. AGENTS.md
# § Commands states the consequence: a run can execute a subset and still
# report success. The exit code is 0 in every one of those cases, so no caller
# reading exit status can tell a full run from a partial one.
#
# MEASURED IN THIS REPOSITORY, 2026-08-06, three real runs:
#
#   sbt testFull            -> "Total number of tests run: 4", Suites: 3, exit 0
#   sbt test  (unchanged)   -> NO summary block at all; "No tests to run for
#                              Test / testQuick"; "Passed: Total 0"; exit 0
#   sbt test  (1 spec edited) -> "Total number of tests run: 2", Suites: 1,
#                              "All tests passed."                     exit 0
#
# The third is the dangerous one and it is why this script exists: two of four
# tests ran, the log says "All tests passed", and the exit code says 0. Nothing
# in that run is false; it is simply not the claim a reader takes from it.
#
# WHERE THE EXPECTED TOTAL COMES FROM. A `testFull` run's own
# "Total number of tests run" line -- never a hand count of spec files.
# Measured here: 3 spec files, 4 tests. Counting files gives 3 and would make
# a real full run look partial. The number lives in a tracked file so that
# changing it is a visible, separate edit in the diff rather than something a
# caller passes on the command line and nobody reviews.
#
# READ-ONLY. It parses a file and reports. It never invokes sbt -- so it is
# usable in a review, on CI output, or on a log someone pasted, and its own
# proof does not need a JVM.
set -uo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 2
EXPECTED_FILE="$REPO/scripts/test-expected-total.txt"

LOG=${1:-}
if [ -z "$LOG" ]; then
  echo "ERROR: no log given."
  echo "Usage: scripts/check-test-run.sh <sbt-output-log> [expected-total]"
  exit 2
fi
if [ ! -f "$LOG" ]; then
  echo "ERROR: no such log: $LOG"
  exit 2
fi

# Expected total: the argument if given, else the tracked reference file.
EXPECTED=${2:-}
if [ -z "$EXPECTED" ]; then
  if [ ! -f "$EXPECTED_FILE" ]; then
    echo "ERROR: no expected total. Pass one, or create $EXPECTED_FILE"
    echo "       from a 'sbt testFull' run's own 'Total number of tests run' line."
    exit 2
  fi
  EXPECTED=$(tr -dc '0-9' <"$EXPECTED_FILE")
fi

case "$EXPECTED" in
  '' | *[!0-9]*)
    echo "ERROR: expected total is not a number: '${EXPECTED}'"
    exit 2
    ;;
esac

# ScalaTest prints one summary block per run. Take the LAST, so a log holding
# several runs is judged on its final one rather than on whichever came first.
ACTUAL=$(grep -oE 'Total number of tests run: [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')
SUITES=$(grep -oE 'Suites: completed [0-9]+' "$LOG" | tail -1 | grep -oE '[0-9]+$')

echo "log      : $LOG"
echo "expected : $EXPECTED test(s)"

# THE COULD-NOT-RUN CASE, and it is the common one. A cached sbt run that
# executes nothing prints no summary block at all -- so "no count found" is
# not a malformed log, it is the empty-cache run this check exists to catch.
# Reporting 0 executed would be a finding; reporting *clean* would be a lie.
if [ -z "$ACTUAL" ]; then
  echo "executed : (no summary block in this log)"
  if grep -qE 'No tests to run' "$LOG"; then
    echo
    echo "FAIL: this run executed NOTHING. sbt reported success because its"
    echo "      cache believed nothing had changed. That is not a passing test"
    echo "      run and must not be read as one."
    echo "      Re-run with 'sbt testFull', which bypasses the cache."
    exit 2
  fi
  echo
  echo "ERROR: no ScalaTest summary found and no 'No tests to run' marker."
  echo "       This log does not describe a test run at all -- check the path,"
  echo "       and note that a build failure produces no summary either."
  exit 2
fi

echo "executed : $ACTUAL test(s) in ${SUITES:-?} suite(s)"

# Zero expected is not a bar anything can clear. Treat it as an unusable
# reference rather than a pass, or a stale 0 would certify every run.
if [ "$EXPECTED" -eq 0 ]; then
  echo
  echo "ERROR: the expected total is 0, so this check can never fail."
  echo "       Regenerate it from a 'sbt testFull' run."
  exit 2
fi

if [ "$ACTUAL" -lt "$EXPECTED" ]; then
  echo
  echo "FAIL: PARTIAL RUN — $ACTUAL of $EXPECTED test(s) executed."
  echo "      The log may well say 'All tests passed'. It is saying that about"
  echo "      the subset it ran, not about the suite. A non-zero count is not"
  echo "      evidence of a full run."
  echo "      Re-run with 'sbt testFull' before treating this as a pass."
  exit 1
fi

if [ "$ACTUAL" -gt "$EXPECTED" ]; then
  echo
  echo "FAIL: $ACTUAL test(s) ran but only $EXPECTED were expected."
  echo "      Tests were almost certainly added. This is not a defect in the"
  echo "      run — update scripts/test-expected-total.txt from a 'testFull'"
  echo "      run, as a visible edit, so the new total is reviewed."
  exit 1
fi

echo
echo "PASS: $ACTUAL of $EXPECTED test(s) executed — a full run."
exit 0
