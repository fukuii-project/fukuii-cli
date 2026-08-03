#!/bin/bash
# Prove that scripts/check-gitignore.sh can actually FAIL.
#
# Usage: scripts/check-gitignore-proof.sh          exit 0 = proof holds
#
# WHY THIS EXISTS. A check that has only ever returned PASS is indistinguishable
# from one that cannot fail. The sweep this validates once returned PASS over a
# .gitignore that left an encrypted keystore and a plaintext BIP-39 mnemonic
# committable -- it passed because none of its cases exercised the defect, not
# because the file was sound. So the sweep's verdict means nothing on its own;
# it means something only alongside a demonstration that a known-bad input makes
# it fail.
#
# TWO DEFECTS IN EARLIER VERSIONS, both fixed here, both worth knowing because
# each is a shape that recurs:
#
# 1. THE REFERENCE WAS PINNED TO A MOVING REF. ARM 1 read its known-bad input
#    from `git show HEAD:.gitignore`. The commit carrying the .gitignore fix was
#    later AMENDED, which moved HEAD onto the fixed file -- so ARM 1 began
#    comparing the fixed file against itself, passed, and the script printed its
#    own failure banner. The artifact whose only job was ruling out a placebo
#    became one. A proof must resolve its reference through something COMMITTED
#    and IMMOVABLE BY THE CHANGE UNDER TEST; a ref the change moves is neither,
#    and a gitignored fixture fails the first half.
#
# 2. IT MUTATED THE LIVE GATE WHILE RUNNING. It overwrote the repository's real
#    .gitignore with the defective version for the duration of ARM 1, restoring
#    on EXIT. During that window the gate was DOWN in the working tree, and
#    `trap ... EXIT` does not fire on SIGKILL, OOM or power loss -- so an
#    interruption left the defective file in place looking exactly like the
#    correct one. Both arms now run against throwaway repos and this one is
#    never touched.
set -uo pipefail

REPO=$(cd "$(dirname "$0")/.." && pwd) || exit 1
SWEEP="$REPO/scripts/check-gitignore.sh"
KNOWN_BAD="$REPO/scripts/fixtures/gitignore.known-bad"

for f in "$SWEEP" "$KNOWN_BAD"; do
  [ -f "$f" ] || { echo "missing: $f"; exit 1; }
done

# A throwaway repo per arm. `git check-ignore` needs a real repository, and the
# sweep takes its root as a parameter precisely so this is possible.
arm() {  # arm <label> <gitignore-source> <expected-exit>
  local label=$1 src=$2 want=$3 tmp rc
  tmp=$(mktemp -d) || return 1
  git -C "$tmp" init -q
  cp "$src" "$tmp/.gitignore"
  bash "$SWEEP" "$tmp" > "$tmp/out.txt" 2>&1
  rc=$?
  echo "--- $label"
  echo "    gitignore under test : $src"
  echo "    sweep exit           : $rc (expected $want)"
  if [ "$rc" != "$want" ]; then
    echo "    *** UNEXPECTED ***"
    grep -E '^  (FAIL|ERROR)' "$tmp/out.txt" | head -5 | sed 's/^/    /'
  else
    grep -E '^  (FAIL|ERROR)' "$tmp/out.txt" | head -6 | sed 's/^/    /'
    echo "    failing cases        : $(grep -cE '^  (FAIL|ERROR)' "$tmp/out.txt")"
  fi
  rm -rf "$tmp"
  [ "$rc" = "$want" ]
}

echo "############ ARM 1 — the KNOWN-BAD fixture. The sweep MUST fail. ############"
arm "known-bad fixture" "$KNOWN_BAD" 1; arm1=$?

echo
echo "############ ARM 2 — this repo's real .gitignore. The sweep MUST pass. ######"
arm "current /.gitignore" "$REPO/.gitignore" 0; arm2=$?

echo
if [ "$arm1" = 0 ] && [ "$arm2" = 0 ]; then
  echo "PROOF HOLDS: the sweep fails on a known defect and passes on the real file."
  echo "Neither arm touched this repository's .gitignore."
  exit 0
fi
echo "PROOF DOES NOT HOLD (arm1 ok=$arm1, arm2 ok=$arm2). A sweep that cannot"
echo "fail proves nothing about the file it certifies."
exit 1
