#!/bin/bash
# Prove that scripts/check-debug-instrumentation.sh can actually FAIL, can
# actually PASS, and can tell "clean" apart from "scanned nothing".
#
# Usage: scripts/check-debug-instrumentation-proof.sh     exit 0 = proof holds
#
# WHY THIS EXISTS. A check that has only ever returned PASS is indistinguishable
# from one that cannot fail. This repository's sibling proof for the .gitignore
# sweep exists for the same reason and is the shape followed here.
#
# WHAT MAKES ARM 3 THE POINT. The command this collector replaced returned the
# SAME exit code for "scanned 3 files, found nothing" and "scanned nothing at
# all". Arm 3 is the arm that would have caught that, and it is the reason the
# collector exists rather than the one-liner.
#
# WHERE THE KNOWN-BAD REFERENCE RESOLVES FROM. A tracked fixture directory at a
# stable path -- never `git show HEAD:...`, never the live file under test.
# A moving ref is what turned this repository's earlier .gitignore proof into a
# placebo: an amend moved HEAD onto the fixed file and the arm began comparing
# that file against itself. The fixtures here can change only if someone edits
# the fixtures, visibly, in the diff.
#
# NOTHING HERE TOUCHES THIS REPOSITORY. Every arm builds a throwaway git repo
# in a temp directory and runs the real collector against it by parameter. The
# collector is never asked to mutate anything and this tree is never modified,
# so an interrupted run cannot leave the repository in a checked-out bad state.
set -uo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 1
CHECK="$REPO/scripts/check-debug-instrumentation.sh"
BAD_DIR="$REPO/scripts/fixtures/debug-instrumentation.known-bad"
GOOD_DIR="$REPO/scripts/fixtures/debug-instrumentation.known-good"

for p in "$CHECK" "$BAD_DIR" "$GOOD_DIR"; do
  [ -e "$p" ] || { echo "missing: $p"; exit 1; }
done

# Build a throwaway repo from a fixture directory, renaming *.scala.txt to
# *.scala so the collector's real `git ls-files '*.scala'` discovery runs. This
# is the integration path, not a call into an internal function.
build_repo() { # build_repo <fixture-dir-or-empty> -> prints tmpdir
  local src=$1 tmp f base
  tmp=$(mktemp -d) || return 1
  git -C "$tmp" init -q
  git -C "$tmp" config user.email proof@localhost
  git -C "$tmp" config user.name proof
  if [ -n "$src" ]; then
    for f in "$src"/*.scala.txt; do
      [ -e "$f" ] || continue
      base=$(basename "$f" .txt)
      cp "$f" "$tmp/$base"
    done
  fi
  git -C "$tmp" add -A >/dev/null 2>&1
  git -C "$tmp" commit -qm fixture >/dev/null 2>&1 || true
  printf '%s' "$tmp"
}

arm() { # arm <label> <fixture-dir-or-empty> <expected-exit> [checker]
  local label=$1 src=$2 want=$3 checker=${4:-$CHECK} tmp rc out
  tmp=$(build_repo "$src") || return 1
  out=$(bash "$checker" "$tmp" 2>&1)
  rc=$?
  echo "--- $label"
  printf '%s\n' "$out" | sed 's/^/    /'
  echo "    exit: $rc (expected $want)"
  [ "$rc" != "$want" ] && echo "    *** UNEXPECTED ***"
  rm -rf "$tmp"
  [ "$rc" = "$want" ]
}

echo "###### ARM 1 — known-bad fixture. The check MUST report findings. ######"
arm "known-bad" "$BAD_DIR" 1; a1=$?

echo
echo "###### ARM 2 — known-good fixture. The check MUST pass. ################"
arm "known-good" "$GOOD_DIR" 0; a2=$?

echo
echo "###### ARM 3 — EMPTY tree. Must be exit 2, NOT exit 0. ################"
echo "       This is the defect the collector exists to fix: the command it"
echo "       replaced returned the same code here as for a genuine clean run."
arm "empty repo (no .scala at all)" "" 2; a3=$?

echo
echo "###### ARM 4 — not a git repository at all. Must be exit 2. ###########"
NOTREPO=$(mktemp -d) || exit 1
out=$(bash "$CHECK" "$NOTREPO" 2>&1); rc=$?
printf '%s\n' "$out" | sed 's/^/    /'
echo "    exit: $rc (expected 2)"
rm -rf "$NOTREPO"
if [ "$rc" = 2 ]; then a4=0; else a4=1; fi

echo
echo "###### ARM 5 — WHICH cases fire, not merely that something did. #######"
echo "       An arm that fails for an incidental reason is a placebo for the"
echo "       class it claims to cover. Every fixture case must be named."
tmp=$(build_repo "$BAD_DIR") || exit 1
hits=$(bash "$CHECK" "$tmp" 2>&1)
rm -rf "$tmp"
a5=0
for pat in 'println' 'System\.out\.print' 'System\.err\.print' 'printStackTrace' 'MITHRIL-DEBUG' 'TEMP DEBUG'; do
  if printf '%s' "$hits" | grep -qE "$pat"; then
    echo "    caught: $pat"
  else
    echo "    MISSED: $pat  <-- fixture case not reported"
    a5=1
  fi
done

# Every check above is PATTERN-scoped, and that is not sufficient here. The
# NUL-byte fixture's violation is an ordinary println, so another fixture file
# supplies that pattern and the loop passes whether or not the binary file was
# read at all. This case is FILE-scoped for that reason: grep classifies a
# NUL-bearing file as binary and reports its match on stderr, so without -a the
# filename never reaches stdout while every pattern above still fires.
if printf '%s' "$hits" | grep -q 'NulByte\.scala'; then
  echo "    caught: NulByte.scala (the binary-classified file was actually read)"
else
  echo "    MISSED: NulByte.scala  <-- a NUL byte makes this file invisible to the gate"
  a5=1
fi

echo
echo "###### ARM 6 — seed a PLAUSIBLE regression; the fixture must catch it. #"
echo "       Not a total ablation. This anchors the println pattern at line"
echo "       start, which is what a real 'tighten the regex' edit looks like."
MUTANT=$(mktemp) || exit 1
sed "s/^PATTERNS='println|/PATTERNS='^println|/" "$CHECK" > "$MUTANT"
if cmp -s "$CHECK" "$MUTANT"; then
  echo "    *** the mutation did not apply — the anchor line moved. ***"
  a6=1
else
  tmp=$(build_repo "$BAD_DIR") || exit 1
  mout=$(bash "$MUTANT" "$tmp" 2>&1); mrc=$?
  rm -rf "$tmp"
  # The mutant must STOP reporting the indented-println case. If it still
  # reports it, the fixture's case 1 is not discriminating and the control is
  # weaker than it looks.
  if printf '%s' "$mout" | grep -qE 'println\("entering compute"\)'; then
    echo "    *** MUTANT SURVIVED — fixture case 1 does not discriminate ***"
    a6=1
  else
    echo "    mutant exit: $mrc — the indented println is no longer reported,"
    echo "    so fixture case 1 is what pins that half of the pattern."
    a6=0
  fi
fi
rm -f "$MUTANT"

echo
echo "###### ARM 7 — an UNTRACKED violation. The gate runs before a commit. #####"
# This arm exists because the gate did NOT hold here until 2026-08-19. It derived
# its file list from `git ls-files`, which lists tracked files only -- and this
# check runs before a task is declared done, which is before committing. New work
# was therefore invisible to it, and the file-count discriminator did not notice,
# because the tracked count is non-zero on its own.
#
# The arm is built from the known-GOOD fixture so that the tracked half is clean:
# a hit here can only have come from the untracked file, which is what makes the
# arm discriminating rather than merely passing.
UNTRACKED_TMP=$(build_repo "$GOOD_DIR") || exit 1
printf 'object Untracked { println("planted") }\n' > "$UNTRACKED_TMP/Untracked.scala"
u_out=$("$CHECK" "$UNTRACKED_TMP" 2>&1); u_rc=$?
u_scanned=$(printf '%s' "$u_out" | sed -n 's/^scanned: \([0-9]*\).*/\1/p')
if [ "$u_rc" = 1 ] && printf '%s' "$u_out" | grep -q 'Untracked.scala'; then
  echo "  arm 7 OK   -- untracked violation reported (exit 1, scanned $u_scanned)"
  a7=0
else
  echo "  arm 7 FAIL -- untracked violation NOT reported (exit $u_rc, scanned $u_scanned)"
  echo "               the gate is blind to files that have not been committed,"
  echo "               which is every file at the moment this check is meant to run"
  a7=1
fi
rm -rf "$UNTRACKED_TMP"

echo
if [ "$a1" = 0 ] && [ "$a2" = 0 ] && [ "$a3" = 0 ] && [ "$a4" = 0 ] && [ "$a5" = 0 ] && [ "$a6" = 0 ] && [ "$a7" = 0 ]; then
  echo "PROOF HOLDS: fails on known-bad, passes on known-good, reports exit 2"
  echo "for an empty scan and for a non-repo, names every fixture case, catches a"
  echo "plausible regression, and SEES AN UNTRACKED FILE. No arm touched this"
  echo "repository."
  exit 0
fi
echo "PROOF DOES NOT HOLD (arm1=$a1 arm2=$a2 arm3=$a3 arm4=$a4 arm5=$a5 arm6=$a6 arm7=$a7)."
exit 1
