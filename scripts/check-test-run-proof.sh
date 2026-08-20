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
MM_FULL="$REPO/scripts/fixtures/test-run-multimodule-full.txt"
MM_PARTIAL="$REPO/scripts/fixtures/test-run-multimodule-partial.txt"
CERT_CANCELED="$REPO/scripts/fixtures/test-run-certification-canceled.txt"

for p in "$CHECK" "$FULL" "$PARTIAL" "$EMPTY" "$MM_FULL" "$MM_PARTIAL" "$CERT_CANCELED"; do
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
sed 's/if \[ "\$ACCOUNTED" -lt "\$EXPECTED" \]; then/if [ "$ACCOUNTED" -lt 1 ]; then/' "$CHECK" >"$MUTANT"
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
MUTANT2=$(mktemp) || exit 1
# shellcheck disable=SC2016  # literal sed pattern text, not expansions
sed "s|^ACTUAL=\$(sum_field 'Total number of tests run: \[0-9\]+')|ACTUAL=\$(printf '%s' \"\$RUN\" \| grep -oE 'Total number of tests run: [0-9]+' \| tail -1 \| grep -oE '[0-9]+\$')|" "$CHECK" >"$MUTANT2"
if cmp -s "$CHECK" "$MUTANT2"; then
  echo "    *** the mutation did not apply — the summing line moved. ***"
  a11=1
else
  bash "$MUTANT2" "$MM_FULL" 36 >/dev/null 2>&1
  mrc2=$?
  if [ "$mrc2" = 0 ]; then
    echo "    *** MUTANT SURVIVED — the multi-module fixture does not discriminate ***"
    a11=1
  else
    echo "    mutant exit: $mrc2 — reading only the last block sees 32 of 36 and"
    echo "    rejects a correct full run, so the fixture pins the summing."
    a11=0
  fi
fi
rm -f "$MUTANT2"

echo
echo "###### ARM 12 — the CERTIFICATION TIER canceled. MUST fail. ###########"
echo "       The dangerous case in its purest form, and the one this gate"
echo "       reported as a PASS until 2026-08-20: every count agrees, sbt says"
echo "       'All tests passed', the shell exits 0, and the tier that certifies"
echo "       against published vectors ran NOTHING. Real output, captured by"
echo "       moving the corpus pointer aside and running the full suite."
arm "401 ran, 24 certification tests canceled, 425 expected" "$CERT_CANCELED" 425 1; a12=$?

echo
echo "###### ARM 13 — the same gate must still PASS a real full run. ########"
echo "       An arm that only ever fails would be satisfied by a gate that"
echo "       rejects everything, which is the mirror of what was wrong here."
arm "full run, nothing canceled" "$FULL" 4 0; a13=$?

echo
echo "###### ARM 14 — WHICH figure arm 12 turns on. ########################"
echo "       It must fail for the certification tier specifically, not because"
echo "       the counts disagree -- they agree exactly, which is the point."
cout=$(bash "$CHECK" "$CERT_CANCELED" 425 2>&1)
a14=0
printf '%s' "$cout" | grep -q 'CERTIFICATION TIER CANCELED' || { echo "    MISSED: not reported as a canceled certification tier"; a14=1; }
printf '%s' "$cout" | grep -qE 'accounted: 425' || { echo "    MISSED: the counts do not agree, so the arm proves something else"; a14=1; }
printf '%s' "$cout" | grep -q 'PARTIAL RUN' && { echo "    MISSED: failed as a partial run, not as a canceled tier"; a14=1; }
grep -q 'All tests passed' "$CERT_CANCELED" || { echo "    MISSED: fixture lacks the misleading line it exists to carry"; a14=1; }
[ "$a14" = 0 ] && echo "    the arm turns on the canceled tier, over a log whose counts agree"
echo "    exactly and which says 'All tests passed'"

echo
echo "###### ARM 15 — seed the regression that WAS shipped. ################"
echo "       Counting canceled tests toward the total without asking WHICH"
echo "       suite canceled is precisely the prior behaviour. The fixture must"
echo "       tell the fixed gate from the one that shipped."
#
# THE MUTANT LIVES INSIDE THE REPOSITORY, and that is load-bearing rather than
# tidy. The script under test resolves its own repository root from its own
# location, so a mutant written to a temporary directory fails for a MISSING
# TREE and not for the mutation -- which is a green arm proving nothing. Both
# arms below were observed doing exactly that before this was fixed.
MUT="$REPO/scripts/.proof-mutant.sh"
trap 'rm -f "$MUT"' EXIT

# shellcheck disable=SC2016  # literal sed pattern text, not expansions
sed 's/^if \[ -n "\$CANCELED_CERTIFICATION" \]; then/if [ -n "" ]; then/' "$CHECK" >"$MUT"
if cmp -s "$CHECK" "$MUT"; then
  echo "    *** the mutation did not apply — the guard line moved. ***"
  a15=1
else
  bash "$MUT" "$CERT_CANCELED" 425 >/dev/null 2>&1
  mrc3=$?
  if [ "$mrc3" != 0 ]; then
    echo "    *** MUTANT SURVIVED — arm 12 does not turn on the new guard (exit $mrc3) ***"
    a15=1
  else
    echo "    mutant exit: 0 — with the guard disabled the gate passes a run"
    echo "    that certified nothing, which is the state that shipped."
    a15=0
  fi
fi
rm -f "$MUT"

echo
echo "###### ARM 16 — the gate must notice its OWN name going stale. #######"
echo "       A rename is how a check silently stops matching, and the suite"
echo "       guarded here was renamed once already."
a16=0
# THE CONTROL COMES FIRST. An unmutated copy at the same path must NOT report a
# stale name; without this, exit 2 from any cause reads as the arm passing, and
# that is exactly how this arm first went green.
cp "$CHECK" "$MUT"
bash "$MUT" "$FULL" 4 >/dev/null 2>&1
ctl=$?
if [ "$ctl" != 0 ]; then
  echo "    *** CONTROL FAILED — an unmutated copy at this path exits $ctl, so"
  echo "        any exit 2 below would be attributable to the path, not the name ***"
  a16=1
else
  echo "    control: an unmutated copy at the same path passes, so what follows"
  echo "    is attributable to the name and not to where the script sits"
  sed 's/^CERT_SUITES="CertificationCorporaSpec"/CERT_SUITES="ASuiteThatWasRenamedAway"/' "$CHECK" >"$MUT"
  if cmp -s "$CHECK" "$MUT"; then
    echo "    *** the mutation did not apply — CERT_SUITES moved. ***"
    a16=1
  else
    mout=$(bash "$MUT" "$FULL" 4 2>&1)
    mrc4=$?
    if [ "$mrc4" != 2 ]; then
      echo "    *** MUTANT SURVIVED — a stale suite name is not reported (exit $mrc4) ***"
      a16=1
    elif ! printf '%s' "$mout" | grep -q 'ASuiteThatWasRenamedAway'; then
      echo "    *** exit 2 for some other reason — the message does not name the suite ***"
      a16=1
    else
      echo "    mutant exit: 2, naming the stale suite — a name matching no source"
      echo "    file is an error rather than a check that quietly never fires."
    fi
  fi
fi
rm -f "$MUT"

echo
if [ "$a1" = 0 ] && [ "$a2" = 0 ] && [ "$a3" = 0 ] && [ "$a4" = 0 ] && [ "$a5" = 0 ] && [ "$a6" = 0 ] && [ "$a7" = 0 ] && [ "$a8" = 0 ] && [ "$a9" = 0 ] && [ "$a10" = 0 ] && [ "$a11" = 0 ] && [ "$a12" = 0 ] && [ "$a13" = 0 ] && [ "$a14" = 0 ] && [ "$a15" = 0 ] && [ "$a16" = 0 ]; then
  echo "PROOF HOLDS: passes a full run, fails a partial one over a log that says"
  echo "'All tests passed', reports exit 2 for an empty run and an unusable"
  echo "reference, sums a multi-project run and catches a module that did not"
  echo "run, FAILS a run whose certification tier canceled while every count"
  echo "agreed, still passes a clean full run, and four plausible regressions"
  echo "are caught -- including the one this gate actually shipped with."
  exit 0
fi
echo "PROOF DOES NOT HOLD (a1=$a1 a2=$a2 a3=$a3 a4=$a4 a5=$a5 a6=$a6 a7=$a7 a8=$a8 a9=$a9 a10=$a10 a11=$a11 a12=$a12 a13=$a13 a14=$a14 a15=$a15 a16=$a16)"
exit 1
