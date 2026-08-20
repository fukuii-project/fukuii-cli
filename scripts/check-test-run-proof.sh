#!/bin/bash
# Prove that scripts/check-test-run.sh can actually FAIL, actually PASS, and
# tells a partial run apart from an empty one.
#
# Usage: scripts/check-test-run-proof.sh          exit 0 = proof holds
#
# NO JVM RUNS HERE, DELIBERATELY. Every fixture is real sbt output captured from
# this repository and committed -- read each one's own header for when and under
# what conditions. A proof that
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
MM_FULL="$REPO/scripts/fixtures/test-run-multimodule-full.txt"
MM_PARTIAL="$REPO/scripts/fixtures/test-run-multimodule-partial.txt"
# The certification pair: two real runs of the same tree, each accounting for
# every test that exists, one of which certified nothing. They differ in the
# `failed` field and almost nothing else, which is what makes them a control
# for that field rather than for the tool.
CERT_FAILED="$REPO/scripts/fixtures/test-run-certification-failed.txt"
CERT_CANCELED="$REPO/scripts/fixtures/test-run-certification-canceled.txt"

for p in "$CHECK" "$FULL" "$PARTIAL" "$EMPTY" "$MM_FULL" "$MM_PARTIAL" "$CERT_FAILED" "$CERT_CANCELED"; do
  [ -f "$p" ] || { echo "missing: $p"; exit 1; }
done

# WHERE A MUTANT LIVES, and why it is no longer written into scripts/.
# It used to be, on the ground that this script resolves its repository root
# from its own location and so needs the tree around it. Measured 2026-08-20,
# that is not true of any arm here: every mutant arm passes an explicit expected
# total, which is the only thing $REPO is used for, and both existing mutants
# produce the identical exit code from a throwaway directory (0 and 1) as from
# inside scripts/. The only shape that differed was an arm omitting the expected
# total, which no arm does.
#
# A throwaway tree is what the checker standard asks for, and it removes a
# gitignored path inside a tracked directory -- a leftover there is invisible to
# `git status` and to the gitignore checker, which is the worst place to put a
# file that is a copy of this gate with its guard disabled.
MUTDIR=$(mktemp -d) || exit 1
trap 'rm -rf "$MUTDIR"' EXIT

# A MUTANT ARM ASSERTS AN EXACT EXIT CODE, never "anything but the target".
# Accepting `!= target` cannot tell "died of the seeded mutation" from "died of
# something else", and both arms below did exactly that: measured 2026-08-20,
# the ARM 8 mutant exits 0 against the real fixture and 2 against a missing log
# path, and an inequality test calls both a kill while printing the same
# affirmative conclusion.
mutant_arm() { # mutant_arm <sed-expr> <log> <expected-total> <want-exit>
  local expr=$1 log=$2 exp=$3 want=$4 mut rc
  mut="$MUTDIR/mutant.sh"
  sed "$expr" "$CHECK" >"$mut"
  if cmp -s "$CHECK" "$mut"; then
    echo "    *** the mutation did not apply -- the target line moved. ***"
    rm -f "$mut"
    return 1
  fi
  bash "$mut" "$log" "$exp" >/dev/null 2>&1
  rc=$?
  rm -f "$mut"
  if [ "$rc" = "$want" ]; then
    echo "    mutant exit: $rc, exactly the seeded behaviour (expected $want)."
    return 0
  fi
  echo "    *** MUTANT exit $rc, expected EXACTLY $want. Either it survived, or"
  echo "    *** it died of something other than the mutation. Proves nothing."
  return 1
}

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
echo "       Comparing against zero passes the partial fixture outright, so the"
echo "       seeded behaviour is exit 0 and nothing else."
# shellcheck disable=SC2016  # the $-forms are LITERAL sed pattern text, not
# expansions -- the point is to rewrite the comparison in the target script.
mutant_arm 's/if \[ "\$ACCOUNTED" -lt "\$EXPECTED" \]; then/if [ "$ACCOUNTED" -lt 1 ]; then/' \
  "$PARTIAL" 4 0
a8=$?

echo
echo "###### ARM 9 — MULTI-MODULE: one testFull, one summary block PER project."
echo "       sbt prints a block per project, so the repository total is the SUM."
arm "two project blocks, 4 + 32, expected 36" "$MM_FULL" 36 0; a9=$?

echo
echo "###### ARM 10 — MULTI-MODULE with one project's block absent. MUST fail."
echo "       This is what a module silently not running looks like."
arm "one project block only, expected 36" "$MM_PARTIAL" 36 1; a10=$?

echo
echo "###### ARM 11 — seed the ORIGINAL single-project shape as the mutant. ##"
echo "       Reading only the LAST block was this script's own prior behavior."
echo "       It is the regression these two fixtures exist to pin, so it is the"
echo "       mutant worth seeding: a fixture that cannot tell the old shape from"
echo "       the new one would be drawn from cases the code already handles."
echo "       Reading only the last block sees 32 of 36 and rejects a correct"
echo "       full run, so the seeded behaviour is exit 1 and nothing else."
# shellcheck disable=SC2016  # literal sed pattern text, not expansions
mutant_arm "s|^ACTUAL=\$(sum_field 'Total number of tests run: \[0-9\]+')|ACTUAL=\$(printf '%s' \"\$RUN\" \| grep -oE 'Total number of tests run: [0-9]+' \| tail -1 \| grep -oE '[0-9]+\$')|" \
  "$MM_FULL" 36 1
a11=$?

echo
echo "###### ARM 12 — A RUN THAT ACCOUNTED FOR EVERYTHING AND CERTIFIED NOTHING."
echo "       Both fixtures are real runs of this repository whose accounting is"
echo "       PERFECT -- executed plus canceled equals the expected total exactly."
echo "       One of them failed. Every count in this check clears for both, so"
echo "       the count cannot be what separates them."
arm "corpus absent: 908 ran + 25 canceled = 933, one FAILED" "$CERT_FAILED" 933 1; a12a=$?
arm "canceled but nothing failed: 401 + 24 = 425" "$CERT_CANCELED" 425 0; a12b=$?
if [ "$a12a" = 0 ] && [ "$a12b" = 0 ]; then a12=0; else a12=1; fi

echo
echo "###### ARM 13 — WHICH figure ARM 12 turns on. ########################"
echo "       The control here is a member of the class being counted: both"
echo "       fixtures account for every test, and both carry canceled tests."
echo "       Only 'failed' differs, so only 'failed' can be what fired."
cout=$(bash "$CHECK" "$CERT_FAILED" 933 2>&1)
kout=$(bash "$CHECK" "$CERT_CANCELED" 425 2>&1)
a13=0
printf '%s' "$cout" | grep -qE 'failed   : 1 test' || { echo "    MISSED: did not read 1 as the failed count"; a13=1; }
printf '%s' "$cout" | grep -qE 'accounted: 933' || { echo "    MISSED: did not account for all 933 -- it would have failed on the count"; a13=1; }
printf '%s' "$cout" | grep -qE 'FAIL: 1 test\(s\) FAILED' || { echo "    MISSED: not reported as a failed run"; a13=1; }
printf '%s' "$kout" | grep -qE 'canceled : 24 test' || { echo "    MISSED: control carries no canceled tests, so it cannot rule canceled out"; a13=1; }
printf '%s' "$kout" | grep -qE 'failed   : 0 test' || { echo "    MISSED: control is not a known-negative for the failed field"; a13=1; }
printf '%s' "$kout" | grep -q 'PASS' || { echo "    MISSED: control did not pass, so the arm does not isolate 'failed'"; a13=1; }
grep -q 'All tests passed' "$CERT_FAILED" || { echo "    MISSED: known-bad lacks the misleading lines it exists to carry"; a13=1; }
[ "$a13" = 0 ] && echo "    fires on failed=1 with the count perfect; silent on canceled=24 alone."

echo
echo "###### ARM 14 — seed the regression that IS this gate's history. ######"
echo "       Three fixes to this gate were believed complete while it still"
echo "       passed a run that failed. Deleting the refusal is that state, and"
echo "       the known-bad fixture must be what refuses to let it back."
# shellcheck disable=SC2016  # literal sed pattern text, not expansions
mutant_arm 's/^if \[ "\$FAILED" -gt 0 \]; then/if [ "$FAILED" -gt 999999 ]; then/' \
  "$CERT_FAILED" 933 0
a14=$?

echo
echo "###### ARM 15 — the field going away must not read as zero failures. ##"
echo "       A pattern that stops matching reports 0, and 0 is what a clean run"
echo "       looks like -- the exact shape every previous fail-open here had."
echo "       So the LOG is mutated rather than the script: this is ScalaTest"
echo "       renaming the field, which no edit to this repository can prevent."
DRIFT="$MUTDIR/renamed-field.log"
sed 's/, failed \([0-9]\+\)/, unsuccessful \1/' "$CERT_FAILED" >"$DRIFT"
a15=0
if cmp -s "$CERT_FAILED" "$DRIFT"; then
  echo "    *** the log mutation did not apply -- the field spelling moved. ***"
  a15=1
else
  bash "$CHECK" "$DRIFT" 933 >/dev/null 2>&1
  drc=$?
  if [ "$drc" = 2 ]; then
    echo "    exit: 2 -- reported as unreadable rather than as zero failures."
  else
    echo "    *** exit $drc, expected EXACTLY 2. A field that stopped matching"
    echo "    *** was read as a clean run. This is the fail-open, again. ***"
    a15=1
  fi
fi
rm -f "$DRIFT"

echo
echo
if [ "$a1" = 0 ] && [ "$a2" = 0 ] && [ "$a3" = 0 ] && [ "$a4" = 0 ] && [ "$a5" = 0 ] && [ "$a6" = 0 ] && [ "$a7" = 0 ] && [ "$a8" = 0 ] && [ "$a9" = 0 ] && [ "$a10" = 0 ] && [ "$a11" = 0 ] && [ "$a12" = 0 ] && [ "$a13" = 0 ] && [ "$a14" = 0 ] && [ "$a15" = 0 ]; then
  echo "PROOF HOLDS: passes a full run, fails a partial one over a log that says"
  echo "'All tests passed', reports exit 2 for an empty run and an unusable"
  echo "reference, sums a multi-project run and catches a module that did not"
  echo "run, refuses a run that accounted for every test and failed one of them"
  echo "while passing a control that differs only in that field, reports a field"
  echo "that stopped matching as unreadable rather than as zero, and three"
  echo "plausible regressions are caught by their exact seeded exit code."
  exit 0
fi
echo "PROOF DOES NOT HOLD (a1=$a1 a2=$a2 a3=$a3 a4=$a4 a5=$a5 a6=$a6 a7=$a7 a8=$a8 a9=$a9 a10=$a10 a11=$a11 a12=$a12 a13=$a13 a14=$a14 a15=$a15)"
exit 1
