#!/bin/bash
# Did that test run actually run the tests?
#
# Usage: scripts/check-test-run.sh <sbt-output-log> [expected-total]
#   exit 0 = the log shows a FULL run that PASSED: executed count == expected
#            total, and nothing failed
#   exit 1 = the log shows a PARTIAL run, a count that disagrees, or a run in
#            which a test FAILED
#   exit 2 = could not run: no log, no summary in it, no expected total, or a
#            summary this script could not parse
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

# ScalaTest prints one summary block PER PROJECT, not per run, so a multi-module
# build emits several blocks from a single `testFull`. Measured here the day the
# module tree landed: one `testFull` printed 4 (root) and 32 (bytes).
#
# WHY SUMMING, AND WHY THE OLD `tail -1` WAS THE DANGEROUS SHAPE. Reading only
# the last block reports one project's count as if it were the repository's. The
# resulting mismatch looks like "tests were added", and this script's own advice
# is then to raise the expected total -- to the LAST PROJECT'S count. Do that and
# the check silently stops covering every other project, while still printing
# PASS. That is a check that cannot fail, arrived at by following its own
# guidance.
#
# TWO BLOCKS CAN ALSO MEAN TWO RUNS, which is what `tail -1` was really for. So
# scope to the last run first -- the wrapper writes a `## sbt-run.sh started`
# header per invocation -- and sum within it. A log with no such header (a paste,
# or CI output) has every block summed, which is correct for a single run and is
# the only safe reading when nothing marks the boundaries.
RUN=$(awk '/^## sbt-run\.sh started/ { buf = "" } { buf = buf $0 "\n" } END { printf "%s", buf }' "$LOG")

# Takes the COMPLETE pattern. An earlier form appended ": [0-9]+" to a field
# name, which silently failed for `Suites: completed 4` -- that line separates
# its number with a space, not a colon -- and the `${SUITES:-?}` fallback below
# rendered the miss as a harmless "?" rather than as an error.
sum_field() {
  printf '%s' "$RUN" | grep -oE "$1" | grep -oE '[0-9]+$' |
    awk '{ s += $1 } END { if (NR > 0) print s }'
}

ACTUAL=$(sum_field 'Total number of tests run: [0-9]+')
SUITES=$(sum_field 'Suites: completed [0-9]+')
# A CANCELED TEST IS INVISIBLE TO EVERY OTHER FIGURE IN THIS LOG, and that is a
# ScalaTest property rather than a quirk of ours: `testsCompletedCount` is
# succeeded + failed, so a canceled test appears in NO total. A suite that
# cancels therefore reports "All tests passed", exits 0, and this check used to
# say PASS -- while the thing it canceled out of was the only tier that
# certifies anything. Counting them is what makes that state nameable.
CANCELED=$(sum_field 'canceled [0-9]+')
CANCELED=${CANCELED:-0}
# A FAILED TEST IS THE ONE THING THIS SCRIPT DID NOT LOOK AT, and the counts
# above cannot infer it: a run where the certification tier fails and the rest
# cancels accounts for every test that exists, so every figure here clears while
# nothing was certified. Measured in this repository, 2026-08-20, with the
# corpus pointer moved aside: 908 executed + 25 canceled = 933 expected, sbt
# exited 1, and this script printed PASS.
#
# It is read off the SAME `Tests: succeeded N, failed M, canceled K` line that
# already supplies the canceled count, so it adds no coupling this script did
# not already have.
FAILED=$(sum_field 'failed [0-9]+')
FAILED=${FAILED:-0}
BLOCKS=$(printf '%s' "$RUN" | grep -cE 'Total number of tests run: [0-9]+')
# WHY EACH FIELD IS COUNTED AS WELL AS SUMMED. `sum_field` returning nothing is
# indistinguishable from a genuine zero once ${VAR:-0} has run, so a field that
# stops matching -- ScalaTest renaming it, or reflowing the line -- would report
# "0 failed" forever. Requiring one match per summary block turns that into a
# parse error instead. This is the failure the `Suites: completed` pattern
# already had once, caught then only because a "?" appeared in the output.
# Counted with the SAME instrument sum_field sums with -- every match, not
# every matching line -- so the two figures are comparable. A spurious match
# (a test name carrying "failed 3") therefore also trips this, in the safe
# direction: over-counting is reported as unreadable rather than added in.
FAILED_SEEN=$(printf '%s' "$RUN" | grep -oE 'failed [0-9]+' | wc -l)
CANCELED_SEEN=$(printf '%s' "$RUN" | grep -oE 'canceled [0-9]+' | wc -l)

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

echo "executed : $ACTUAL test(s) in ${SUITES:-?} suite(s), summed over ${BLOCKS} project block(s)"
ACCOUNTED=$((ACTUAL + CANCELED))
if [ "$CANCELED" -gt 0 ]; then
  echo "canceled : $CANCELED test(s) -- these ran NOTHING and are in no other total"
  echo "accounted: $ACCOUNTED (executed + canceled), which is what the total below counts"
fi
echo "failed   : $FAILED test(s)"

if [ "$FAILED_SEEN" != "$BLOCKS" ] || [ "$CANCELED_SEEN" != "$BLOCKS" ]; then
  echo
  echo "ERROR: this log has $BLOCKS summary block(s) but $FAILED_SEEN 'failed' and"
  echo "       $CANCELED_SEEN 'canceled' field(s). One of them is not being read, so the"
  echo "       figures above cannot be trusted -- a field that stops matching"
  echo "       reports zero, and zero is what a clean run looks like."
  echo "       Do not read this as a pass. Fix the pattern before relying on it."
  exit 2
fi

# BEFORE THE COUNT COMPARISONS, DELIBERATELY. A failed test is a fact about the
# run and does not depend on the reference total being right, or usable, or even
# present -- so it must not sit behind a check that can exit first.
if [ "$FAILED" -gt 0 ]; then
  echo
  echo "FAIL: $FAILED test(s) FAILED in this run."
  echo "      The counts above may account for every test that exists. They do"
  echo "      not say the run passed, and this one did not: a suite that fails"
  echo "      while the rest of its cases cancel accounts for everything and"
  echo "      certifies nothing."
  echo "      Read the failures in the log before treating any figure here as"
  echo "      evidence."
  exit 1
fi

# Zero expected is not a bar anything can clear. Treat it as an unusable
# reference rather than a pass, or a stale 0 would certify every run.
# The gate counts tests that EXIST rather than tests that RAN, so a suite whose
# corpus is absent clears it -- correctly, since nothing was added or removed.
# What that must never do is read as certification having happened, which is why
# the canceled count is printed above and called out below.
if [ "$EXPECTED" -eq 0 ]; then
  echo
  echo "ERROR: the expected total is 0, so this check can never fail."
  echo "       Regenerate it from a 'sbt testFull' run."
  exit 2
fi

if [ "$ACCOUNTED" -lt "$EXPECTED" ]; then
  echo
  echo "FAIL: PARTIAL RUN — $ACCOUNTED of $EXPECTED test(s) accounted for."
  echo "      The log may well say 'All tests passed'. It is saying that about"
  echo "      the subset it ran, not about the suite. A non-zero count is not"
  echo "      evidence of a full run."
  echo "      Re-run with 'sbt testFull' before treating this as a pass."
  exit 1
fi

if [ "$ACCOUNTED" -gt "$EXPECTED" ]; then
  echo
  echo "FAIL: $ACCOUNTED test(s) accounted for but only $EXPECTED were expected."
  echo "      Tests were almost certainly added. This is not a defect in the"
  echo "      run — update scripts/test-expected-total.txt from a 'testFull'"
  echo "      run, as a visible edit, so the new total is reviewed."
  exit 1
fi

# ── WHAT THIS SCRIPT SEES OF THE CERTIFICATION TIER, AND WHAT IT DOES NOT ──
#
# The counts answer "was every test that exists accounted for". They cannot
# answer "did the tier that certifies against published vectors actually RUN",
# because a canceled test is accounted for and executes nothing. That gap is
# closed by the failed count above rather than here: the tier asserts its corpus
# is present, so its absence is a FAILURE, and a failure is a field on the same
# summary line every other figure comes from.
#
# WHAT IS DELIBERATELY NOT DONE IS KEYING ON THE SUITE. A guard here did that,
# reading ScalaTest's console text for a suite name, and it was removed rather
# than repaired. Three ways it failed open, all measured: `grep -q` closing the
# pipe early made `pipefail` report 141 and the guard silently stopped firing
# once the upstream produced more than a pipe buffer -- reproduced SILENT at
# 190 KB of upstream output, FIRING at 82 KB, so it would have gone quiet as
# forks were added; it read the whole file while every count reads the last run
# only, so it could contradict its own figures; and reflowing a cancel reason
# onto a second line flipped it from failing to printing its affirmative
# all-clear, with every marker still present. It also needed a hand-maintained
# list of suite names that nothing proved complete.
#
# The common cause was the layer: per-test console text is coupled to how
# ScalaTest chooses to print, and to which suites happen to exist. The summary
# line is not -- it is one line per project, in a fixed field order, and this
# script already reads three other fields off it.
#
# SO THIS SCRIPT NAMES THREE FIGURES AND NO SUITES: what ran, what canceled, and
# what failed. Which suite failed is in the log, and reading it is the reader's
# next step rather than this script's.
echo
if [ "$CANCELED" -gt 0 ]; then
  echo "PASS: all $EXPECTED test(s) accounted for — but $ACTUAL ran and $CANCELED"
  echo "      CANCELED. A canceled test measured nothing and is counted by no"
  echo "      other figure in this log, which is why it is named here. Whether"
  echo "      that matters is the suite's to say, and the certification tier"
  echo "      says so by failing rather than by cancelling."
else
  echo "PASS: $ACTUAL of $EXPECTED test(s) executed — a full run."
fi
exit 0
