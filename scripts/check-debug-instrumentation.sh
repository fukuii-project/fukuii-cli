#!/bin/bash
# Is any debug instrumentation left in the Scala sources?
#
# Usage: scripts/check-debug-instrumentation.sh [repo-root]
#   exit 0 = scanned a non-empty file list and found nothing
#   exit 1 = found something (each hit printed as file:line)
#   exit 2 = could not run: not a repo, or the file list was EMPTY
#
# WHY THIS IS A SCRIPT AND NOT THE ONE-LINER IT REPLACES.
# `.claude/rules/scala3-style.md` states this policy and carried the check as a
# raw command. Measured 2026-08-06, that command cannot distinguish its two
# most important outcomes:
#
#     git ls-files '*.scala' | xargs grep -n 'println\|...'
#       3 files, no violations -> exit 123
#       EMPTY file list        -> exit 123      <- same code, opposite meaning
#
# "Passed" and "never ran" are one exit code. That is the silent-no-op shape:
# a gate that reports success having checked nothing. `xargs -r` does not fix
# it -- measured the same day, -r turns the empty case into exit 0, which
# collapses "found a violation" and "scanned nothing" instead, in the direction
# that reads as success. Only counting the scanned files separates them, and a
# script can do that where a pipeline cannot.
#
# WHAT IT SCANS. Every TRACKED *.scala file, discovered with `git ls-files`.
# Deliberately not a hardcoded path such as src/main: the layout MOVES -- this
# repository's sources began under src/ and now live under modules/*/src/ --
# and a check naming a path that no longer exists matches nothing and reports
# clean forever. `git ls-files` follows the layout wherever it goes, and it cannot
# descend into untracked reference material and report another project's code
# as a finding.
#
# THE CHECK IS TEXTUAL, AND THAT IS A DELIBERATE LIMIT. A comment or a string
# containing one of these words is reported as a hit. There is no attempt to
# strip comments -- that is a parsing layer which would itself need calibrating,
# and the failure direction of a false positive (reword the comment) is far
# cheaper than that of a false negative. Read each hit before acting on it: a
# search returns candidates, a finding requires opening the file.
#
# READ-ONLY. It reports and exits. It never edits a source file, and it takes
# the repository root as a parameter so its own proof can drive it against a
# throwaway tree instead of this one.
set -uo pipefail

REPO=${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)} || exit 2
cd "$REPO" || { echo "ERROR: cannot enter $REPO"; exit 2; }

git rev-parse --git-dir >/dev/null 2>&1 || {
  echo "ERROR: not a git repository: $REPO"
  echo "       This check discovers its inputs with git ls-files."
  exit 2
}

# Each pattern is an instrumentation shape the policy bans. Kept as separate
# alternatives rather than one broad regex so a future edit that drops one is a
# visible deletion, and so the known-bad fixture can carry a case per pattern.
PATTERNS='println|System\.out\.print|System\.err\.print|printStackTrace|[A-Z][A-Z0-9_]*-DEBUG|TEMP DEBUG'

mapfile -t FILES < <(git ls-files '*.scala')
SCANNED=${#FILES[@]}

echo "scanned: $SCANNED tracked .scala file(s)"

# THE DISCRIMINATOR. An empty list is not a pass. Nothing was examined, so the
# run carries no information about the tree, and reporting 0 would hand the
# caller an assurance this script did not earn.
if [ "$SCANNED" -eq 0 ]; then
  echo "ERROR: no tracked .scala files were found, so nothing was checked."
  echo "       This is exit 2 (could not run), never exit 0 (clean) -- a gate"
  echo "       that scanned nothing has not certified anything."
  exit 2
fi

# -a IS LOAD-BEARING, AND ITS ABSENCE WAS A LIVE HOLE IN THIS GATE.
# A source file containing a NUL byte -- which a stray escape in a string
# literal produces, and which renders as an ordinary space in an editor -- is
# classified BINARY by grep. For a binary file grep does not print
# `file:line:match` to stdout; it writes `binary file X matches` to STDERR,
# which the redirect below discards. So the match vanishes, HITS is empty, and
# this gate prints "clean" over a file it did not really read.
#
# Found 2026-08-09 by an independent reviewer, in this repository, in a real
# tracked spec that had acquired a NUL byte. Reproduced end to end: the same
# file with a seeded println returns empty stdout without -a and the correct
# `line:match` with it. The offending byte was removed, but removing it fixes
# ONE FILE and this fixes the CLASS -- any future file that acquires one is
# covered here rather than silently exempt.
HITS=$(grep -anE "$PATTERNS" -- "${FILES[@]}" 2>/dev/null)
RC=$?

# grep exits 1 for "no match" and >1 for a real error. Only the first is clean.
if [ "$RC" -gt 1 ]; then
  echo "ERROR: grep failed (exit $RC) while scanning $SCANNED file(s)."
  exit 2
fi

if [ -n "$HITS" ]; then
  # shellcheck disable=SC2001  # per-line prefixing of a multi-line capture;
  # ${var//} cannot prefix the FIRST line, which is the case that matters here.
  echo "$HITS" | sed 's/^/  FAIL /'
  echo "found: $(printf '%s\n' "$HITS" | wc -l) hit(s) in $SCANNED file(s)"
  echo
  echo "Debug instrumentation does not ship. Instrument the test, not the"
  echo "production source; revert any temporary test-scope logging config."
  echo "See .claude/rules/scala3-style.md, 'Debug instrumentation'."
  exit 1
fi

echo "found: 0 hits in $SCANNED file(s) -- clean"
exit 0
