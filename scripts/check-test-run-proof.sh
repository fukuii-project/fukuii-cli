#!/bin/bash
# Prove that scripts/check-test-run.sh can actually FAIL, actually PASS, and
# tells a partial run apart from an empty one.
#
# Usage: scripts/check-test-run-proof.sh          exit 0 = proof holds
#
# NO JVM RUNS HERE, DELIBERATELY. The three fixtures are real sbt output,
# captured once on 2026-08-06 from this repository, and committed. A proof that
# invoked sbt would take minutes, would need a working toolchain, and -- the
# part that actually matters -- would produce DIFFERENT output on the second
# run, because the very cache under test would have been warmed by the first.
# The subject of this check is a cached run, so a live proof would be
# calibrating against a moving reference.
#
# WHERE THE KNOWN-BAD REFERENCE RESOLVES FROM. Tracked fixture files at stable
# paths under scripts/fixtures/. Not `git show HEAD:`, not a regenerated log,
# not the live tree. They can change only if someone edits them, visibly.
set -uo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 1
CHECK="$REPO/scripts/check-test-run.sh"
FULL="$REPO/scripts/fixtures/test-run-full.txt"
PARTIAL="$REPO/scripts/fixtures/test-run-partial.txt"
EMPTY="$REPO/scripts/fixtures/test-run-empty.txt"

for p in "$CHECK" "$FULL" "$PARTIAL" "$EMPTY"; do
  [ -f "$p" ] || { echo "missing: $p"; exit 1; }
done

arm() { # arm <label> <log> <expected-total-or-empty> <want-exit>
  local label=$1 log=$2 exp=$3 want=$4 rc out
  if [ -n "$exp" ]; then out=$(bash "$CHECK" "$log" "$exp" 2>&1); else out=$(bash "$CHECK" "$log" 2>&1); fi
  rc=$?
  echo "--- $label"
  printf '%s\n' "$out" | sed 's/^/    /'
  echo "    exit: $rc (expected $want)"
  [ "$rc" != "$want" ] && echo "    *** UNEXPECTED ***"
  [ "$rc" = "$want" ]
}

echo "###### ARM 1 — a real FULL run. MUST pass. ############################"
arm "testFull output, 4 of 4" "$FULL" 4 0; a1=$?

echo
echo "###### ARM 2 — a real PARTIAL run. MUST fail. ########################"
echo "       This is the dangerous case: the log itself says 'All tests"
echo "       passed' and sbt exited 0, while 2 of 4 tests actually ran."
arm "cached test after one edit, 2 of 4" "$PARTIAL" 4 1; a2=$?

echo
echo "###### ARM 3 — a real EMPTY run. MUST be exit 2, not 0 and not 1. #####"
echo "       A cached run that executes nothing prints NO summary block, so"
echo "       'no count found' is the empty run, not a malformed log."
arm "cached test, nothing changed" "$EMPTY" 4 2; a3=$?

echo
echo "###### ARM 4 — expected total of 0 must never certify anything. #######"
arm "full log, expected 0" "$FULL" 0 2; a4=$?

echo
echo "###### ARM 5 — missing log, and a non-numeric total. #################"
arm "no such log" "$REPO/scripts/fixtures/does-not-exist.log" 4 2; a5a=$?
arm "non-numeric expected" "$FULL" "four" 2; a5b=$?
if [ "$a5a" = 0 ] && [ "$a5b" = 0 ]; then a5=0; else a5=1; fi

echo
echo "###### ARM 6 — MORE tests than expected must be reported too. ########"
echo "       A stale expected total is a real finding, not a pass."
arm "full log, expected 3 (stale)" "$FULL" 3 1; a6=$?

echo
echo "###### ARM 7 — WHICH figure the partial arm turns on. ################"
echo "       An arm that fails for an incidental reason proves nothing about"
echo "       the class it claims to cover."
pout=$(bash "$CHECK" "$PARTIAL" 4 2>&1)
a7=0
printf '%s' "$pout" | grep -q 'PARTIAL RUN' || { echo "    MISSED: not reported as a partial run"; a7=1; }
printf '%s' "$pout" | grep -qE 'executed : 2 test' || { echo "    MISSED: did not read 2 as the executed count"; a7=1; }
grep -q 'All tests passed' "$PARTIAL" || { echo "    MISSED: fixture lacks the misleading line it exists to carry"; a7=1; }
[ "$a7" = 0 ] && echo "    the arm turns on the count (2), over a log that says 'All tests passed'"

echo
echo "###### ARM 8 — seed a PLAUSIBLE regression; the fixtures must catch it."
echo "       Comparing against zero instead of the expected total is exactly"
echo "       what 'check the count' gets mis-implemented as."
MUTANT=$(mktemp) || exit 1
# shellcheck disable=SC2016  # the $-forms are LITERAL sed pattern text, not
# expansions -- the point is to rewrite the comparison in the target script.
sed 's/if \[ "\$ACTUAL" -lt "\$EXPECTED" \]; then/if [ "$ACTUAL" -lt 1 ]; then/' "$CHECK" >"$MUTANT"
if cmp -s "$CHECK" "$MUTANT"; then
  echo "    *** the mutation did not apply — the comparison line moved. ***"
  a8=1
else
  bash "$MUTANT" "$PARTIAL" 4 >/dev/null 2>&1
  mrc=$?
  if [ "$mrc" = 1 ]; then
    echo "    *** MUTANT SURVIVED — the partial fixture does not discriminate ***"
    a8=1
  else
    echo "    mutant exit: $mrc — comparing against zero stops catching the"
    echo "    partial run, so the fixture is what pins the real comparison."
    a8=0
  fi
fi
rm -f "$MUTANT"

echo
if [ "$a1" = 0 ] && [ "$a2" = 0 ] && [ "$a3" = 0 ] && [ "$a4" = 0 ] && [ "$a5" = 0 ] && [ "$a6" = 0 ] && [ "$a7" = 0 ] && [ "$a8" = 0 ]; then
  echo "PROOF HOLDS: passes a full run, fails a partial one over a log that says"
  echo "'All tests passed', reports exit 2 for an empty run and an unusable"
  echo "reference, and a plausible regression is caught."
  exit 0
fi
echo "PROOF DOES NOT HOLD (a1=$a1 a2=$a2 a3=$a3 a4=$a4 a5=$a5 a6=$a6 a7=$a7 a8=$a8)"
exit 1
