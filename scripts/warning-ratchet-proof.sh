#!/bin/bash
# Prove that the warning ratchet in build.sbt can actually FAIL a build, that it
# does not simply fail everything, and that every category it declares is live.
#
# Usage: scripts/warning-ratchet-proof.sh [target-repo-root]
#   exit 0 = proof holds
#   exit 1 = proof does not hold
#   exit 2 = could not run (no sbt, no fixtures, target has no build.sbt)
#
# WHY THIS EXISTS. A ratchet that has never been observed failing is a ratchet
# you are assuming. `.claude/protocols/warning-ratchet.md` requires both
# directions before a category counts as gated: the build clean with the
# category enabled, AND a deliberately introduced violation actually failing.
#
# WHAT IS UNDER TEST IS build.sbt, NOT A SCRIPT. So every arm copies the
# TARGET's own build definition into a throwaway tree and compiles a fixture
# there. Remove the ratchet from build.sbt and this proof stops holding, which
# is the property that makes it worth running.
#
# NOTHING HERE TOUCHES THE TARGET. The target is read and copied, never
# written, and it is a parameter so the proof can be driven against a synthetic
# tree instead of this one. A verification step whose own failure mode disables
# the thing it verifies is worse than no verification.
#
# ─────────────── Four sbt behaviors this script is built around ───────────────
#
# All four were measured while writing it; each one silently produces a false
# green if ignored.
#
# 1. -Werror ABORTS AFTER THE FIRST FAILING PHASE. A single fixture carrying a
#    violation per category reports only the earliest phase's -- four of the
#    seventeen. So the arm that names WHICH cases fire cannot be the same arm
#    that proves the build fails: it runs the target's real flag list with only
#    -Werror removed, where every category reports. Without that split, deleting
#    -Wunused:all from build.sbt would leave this proof passing.
#
# 2. sbt 2 CACHES COMPILE RESULTS MACHINE-WIDE on (content, flags), and replays
#    a cached success with its warnings STRIPPED -- in a directory that has
#    never been compiled in. Every fixture copy therefore gets a unique trailing
#    comment, so no arm can be answered from cache.
#
#    The gate itself is not at risk from this, and the reason is worth stating:
#    a violating source under -Werror fails, and a failure is not cached as a
#    success. -Werror is what makes the cache harmless here.
#
# 3. NEVER `cp -r project/` WHOLESALE. It carries project/target/active.json,
#    sbt's server pointer, and the copy then drives the ORIGINAL tree's running
#    server -- compiling nothing, in the wrong directory, exiting 0.
#
# 4. `sbt --server`, NEVER BARE `sbt`. Bare sbt starts a BACKGROUND server per
#    invocation that outlives the command. A handful of arms exhausts this
#    machine's inotify instance limit, after which every sbt run on the box
#    fails with "failed to connect to server". --server runs in the foreground
#    and leaves nothing behind. Measured: bare sbt +1 JVM and +4 instances per
#    run, --server zero.
#
# WHY NOT `set -e`. Three arms expect sbt to fail; errexit would abort at the
# first one, before any verdict is computed. Status is accumulated explicitly
# instead, and the one `cd` is guarded.
set -uo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 2
TARGET=${1:-$REPO}

BAD_DIR="$REPO/scripts/fixtures/warning-ratchet.known-bad"
GOOD_DIR="$REPO/scripts/fixtures/warning-ratchet.known-good"
BAD="$BAD_DIR/Violations.scala.txt"
GOOD="$GOOD_DIR/Clean.scala.txt"

# ── could-not-run checks, all before any compile ──
command -v sbt >/dev/null 2>&1 || {
  echo "ERROR: sbt is not on PATH. Nothing was proven."
  exit 2
}
for p in "$BAD" "$GOOD"; do
  [ -f "$p" ] || { echo "ERROR: missing fixture: $p"; exit 2; }
done
[ -f "$TARGET/build.sbt" ] || {
  echo "ERROR: no build.sbt at $TARGET -- there is no ratchet to prove."
  echo "       This is exit 2 (could not run), never exit 0."
  exit 2
}
[ -f "$TARGET/project/build.properties" ] || {
  echo "ERROR: no project/build.properties at $TARGET; sbt version is unpinned."
  exit 2
}

# The category probes. Each is a message fragment the compiler emits for one
# enabled category, so an arm can report WHICH cases fired rather than only
# that something did. Left column is the build.sbt flag the case belongs to.
PROBES=(
  '-Wunused:all|unused-import|unused import'
  '-Wunused:all|unused-private|unused private member'
  '-Wunused:all|unused-local|unused local definition'
  '-Wunused:all|unused-patvar|unused pattern variable'
  '-Wunused:all|unused-explicit|unused explicit parameter'
  '-Wvalue-discard|value-discard|discarded non-Unit value'
  '-Wnonunit-statement|nonunit-statement|unused value of type'
  '-Wtostring-interpolated|tostring-interpolated|interpolation uses toString'
  '-deprecation|deprecation|is deprecated since'
  '-feature|feature|should be enabled'
  '-unchecked|unchecked|cannot be checked at runtime'
  '-Winfer-union|infer-union|inferred to be union type'
  '-Wrecurse-with-default|recurse-with-default|Recursive call used a default argument'
  '-Wenum-comment-discard|enum-comment-discard|Ambiguous Scaladoc comment'
  '-Xlint:all|private-shadow|shadows field'
  '-Xlint:all|type-parameter-shadow|shadows the type defined by'
  '-Ysafe-init|safe-init|Access non-initialized value'
)

# The category this proof deliberately seeds a regression against. Any probe
# would do; this one is chosen because it is the only category the repository's
# own sources had to be adjusted for, so it is the likeliest to be "tidied".
SEED_FLAG='-Wtostring-interpolated'
SEED_CASE='tostring-interpolated'

# ── compile one fixture in a throwaway tree ──
# compile_arm <fixture-file> <build-mode> <overlay-text> <logfile> -> exit code
#   build-mode: "target"  copy the target's real build.sbt
#               "minimal" write a ratchet-free build.sbt instead
compile_arm() {
  local fixture=$1 mode=$2 overlay=$3 log=$4
  local dir rc
  dir=$(mktemp -d) || return 9
  mkdir -p "$dir/project" "$dir/src/main/scala"

  if [ "$mode" = target ]; then
    cp "$TARGET/build.sbt" "$dir/build.sbt" || { rm -rf "$dir"; return 9; }
  else
    # A control build: same Scala version, no ratchet. Read scalaVersion out of
    # the target so the control cannot drift from what it is a control for.
    local sv
    sv=$(grep -oE 'scalaVersion[[:space:]]*:=[[:space:]]*"[^"]+"' "$TARGET/build.sbt" |
      head -1 | grep -oE '"[^"]+"' | tr -d '"')
    [ -n "$sv" ] || { rm -rf "$dir"; return 9; }
    {
      printf 'ThisBuild / scalaVersion := "%s"\n' "$sv"
      printf 'lazy val root = (project in file(".")).settings(name := "control")\n'
    } >"$dir/build.sbt"
  fi

  # build.properties only. Copying project/ wholesale carries active.json.
  cp "$TARGET/project/build.properties" "$dir/project/build.properties" ||
    { rm -rf "$dir"; return 9; }

  cp "$fixture" "$dir/src/main/scala/Subject.scala" || { rm -rf "$dir"; return 9; }
  # Cache-bust: sbt 2 replays a cached compile with its warnings stripped.
  printf '\n// proof cache-bust %s%s\n' "$(date +%s%N)" "$RANDOM" \
    >>"$dir/src/main/scala/Subject.scala"

  # zz- so it sorts after build.sbt and its settings apply last.
  [ -n "$overlay" ] && printf '%s\n' "$overlay" >"$dir/zz-proof-overlay.sbt"

  (cd "$dir" && sbt --server -no-colors -Dsbt.supershell=false \
    'root/compile' >"$log" 2>&1)
  rc=$?
  rm -rf "$dir" 2>/dev/null
  return $rc
}

DROP_WERROR='ThisBuild / scalacOptions ~= (_.filterNot(_ == "-Werror"))'
DROP_WERROR_AND_SEED="ThisBuild / scalacOptions ~= (_.filterNot(Set(\"-Werror\", \"$SEED_FLAG\")))"

LOGDIR=$(mktemp -d) || exit 2
trap 'rm -rf "$LOGDIR"' EXIT

status=0
note() { printf '    %s\n' "$1"; }

echo "target under test: $TARGET"
echo

# ── ARM 1 ── the gate fails on a violating source, in the build's ordinary mode
echo "###### ARM 1 — known-bad fixture, the target's REAL build. Must FAIL. ######"
compile_arm "$BAD" target '' "$LOGDIR/a1.log"
a1rc=$?
a1_errors=$(grep -c '^\[error\]' "$LOGDIR/a1.log" || true)
if [ "$a1rc" -eq 9 ]; then
  echo "    could not set up the arm"
  exit 2
fi
note "sbt exit: $a1rc (expected non-zero), error lines: $a1_errors"
if [ "$a1rc" -ne 0 ] && [ "$a1_errors" -gt 0 ]; then
  note "PASS — a violation is a build failure, not a warning."
  a1=0
else
  note "*** FAIL — the ratchet did not stop a violating source. ***"
  a1=1
  status=1
fi

# ── ARM 2 ── and it does not simply fail everything
echo
echo "###### ARM 2 — known-good fixture, the same REAL build. Must PASS. #########"
note "Same constructs as the known-bad fixture, written correctly. An empty"
note "file would pass under any flag set and would calibrate nothing."
compile_arm "$GOOD" target '' "$LOGDIR/a2.log"
a2rc=$?
a2_warns=$(grep -c '^\[warn\]' "$LOGDIR/a2.log" || true)
note "sbt exit: $a2rc (expected 0), warning lines: $a2_warns"
if [ "$a2rc" -eq 0 ]; then
  note "PASS — the ratchet is satisfiable."
  a2=0
else
  note "*** FAIL — a correct source does not build. The gate is unusable. ***"
  a2=1
  status=1
fi

# ── ARM 3 ── attribute arm 1's failure to the FLAGS, not to a broken fixture
echo
echo "###### ARM 3 — known-bad fixture, a RATCHET-FREE control build. Must PASS. #"
note "Without this, a fixture that simply did not parse would fail ARM 1 and"
note "read as a working gate."
compile_arm "$BAD" minimal '' "$LOGDIR/a3.log"
a3rc=$?
note "sbt exit: $a3rc (expected 0)"
if [ "$a3rc" -eq 0 ]; then
  note "PASS — the fixture is valid Scala; ARM 1 failed on the ratchet."
  a3=0
else
  note "*** FAIL — the fixture does not compile even without the ratchet. ***"
  a3=1
  status=1
fi

# ── ARM 4 ── which categories are live, and -Werror as the load-bearing flag
echo
echo "###### ARM 4 — REAL flag list minus -Werror. Every category must fire. ####"
note "-Werror aborts after the first failing phase, so the arm that names the"
note "cases cannot be the arm that proves failure. This one runs the target's"
note "own flag list with only the promotion removed."
compile_arm "$BAD" target "$DROP_WERROR" "$LOGDIR/a4.log"
a4rc=$?
note "sbt exit: $a4rc (expected 0 — warnings, not errors)"
a4=0
[ "$a4rc" -ne 0 ] && { note "*** FAIL — expected warnings, got a failure. ***"; a4=1; }

for entry in "${PROBES[@]}"; do
  flag=${entry%%|*}
  rest=${entry#*|}
  case_name=${rest%%|*}
  pat=${rest#*|}
  hits=$(grep -cF "$pat" "$LOGDIR/a4.log" || true)
  if [ "$hits" -gt 0 ]; then
    printf '    fired   %-24s %s\n' "$case_name" "$flag"
  else
    printf '    MISSING %-24s %s  <-- category not live\n' "$case_name" "$flag"
    a4=1
  fi
done
if [ "$a4" -eq 0 ]; then
  note "PASS — every declared category reported its own case."
  note "And ARM 1 vs ARM 4: same flags, same source, -Werror the only"
  note "difference, failure vs success. -Werror is what makes this a gate."
else
  note "*** FAIL — a category is declared but not reporting. ***"
  status=1
fi

# ── ARM 5 ── seeded plausible regression, per category
echo
echo "###### ARM 5 — seed a regression: drop ONE category from the flag list. ###"
note "Not a total ablation. This is the edit someone makes while tidying the"
note "list, and ARM 4 must stop reporting exactly that case and no other."
compile_arm "$BAD" target "$DROP_WERROR_AND_SEED" "$LOGDIR/a5.log"
a5rc=$?
a5=0
seed_pat=''
for entry in "${PROBES[@]}"; do
  rest=${entry#*|}
  [ "${rest%%|*}" = "$SEED_CASE" ] && seed_pat=${rest#*|}
done
if [ -z "$seed_pat" ]; then
  note "*** FAIL — the seeded case name is not in the probe list. ***"
  a5=1
else
  seed_hits=$(grep -cF "$seed_pat" "$LOGDIR/a5.log" || true)
  others=0
  others_missing=0
  for entry in "${PROBES[@]}"; do
    rest=${entry#*|}
    name=${rest%%|*}
    [ "$name" = "$SEED_CASE" ] && continue
    others=$((others + 1))
    [ "$(grep -cF "${rest#*|}" "$LOGDIR/a5.log" || true)" -eq 0 ] &&
      others_missing=$((others_missing + 1))
  done
  note "sbt exit: $a5rc; seeded case '$SEED_CASE' hits: $seed_hits (expected 0)"
  note "other cases still reporting: $((others - others_missing))/$others"
  if [ "$seed_hits" -eq 0 ] && [ "$others_missing" -eq 0 ]; then
    note "PASS — removing one flag silences exactly its own case."
  else
    note "*** FAIL — the seeded regression was not isolated. ***"
    a5=1
    status=1
  fi
fi

# ── ARM 6 ── could-not-run is distinct from both verdicts
echo
echo "###### ARM 6 — a target with no build.sbt must be exit 2, not 0 or 1. #####"
EMPTY=$(mktemp -d) || exit 2
bash "$REPO/scripts/warning-ratchet-proof.sh" "$EMPTY" >"$LOGDIR/a6.log" 2>&1
a6rc=$?
rm -rf "$EMPTY"
note "exit: $a6rc (expected 2)"
if [ "$a6rc" -eq 2 ]; then
  note "PASS — 'nothing was checked' is not reported as 'checked and clean'."
  a6=0
else
  note "*** FAIL — an unrunnable proof reported a verdict. ***"
  a6=1
  status=1
fi

# ── coverage disclosure ──
# Every ratchet flag declared in the target's build.sbt that no probe covers.
# Not a failure: some categories cannot be exercised. But an undisclosed gap is
# how a fixture silently falls behind the build it certifies.
echo
echo "###### Declared-but-unexercised categories #################################"
# -Werror is excluded from the gap report because it is not a category: it is
# the promotion, and ARM 1 vs ARM 4 is exactly its positive control -- same
# flags, same source, failure with it and success without.
covered=$(printf '%s\n-Werror\n' "$(printf '%s\n' "${PROBES[@]}" | cut -d'|' -f1)" | sort -u)
declared=$(grep -oE '"-[A-Za-z][^"]*"' "$TARGET/build.sbt" | tr -d '"' |
  grep -vE '^-{1,2}release$' | sort -u)
uncovered=$(comm -23 <(printf '%s\n' "$declared") <(printf '%s\n' "$covered"))
if [ -z "$uncovered" ]; then
  note "none — every flag in build.sbt has a fixture case."
else
  printf '%s\n' "$uncovered" | sed 's/^/    UNEXERCISED  /'
  note "These are declared in build.sbt and no fixture case reaches them."
  note "Reported, not failed: a category may have no constructible trigger."
fi

echo
if [ "$status" -eq 0 ]; then
  echo "PROOF HOLDS: the ratchet fails a violating source and passes a correct"
  echo "one, in the build's ordinary mode; every declared category reports its"
  echo "own case; removing -Werror turns the failure into a pass; removing one"
  echo "category silences exactly that case; and an unrunnable target is exit 2."
  echo "No arm wrote to the target."
  exit 0
fi
echo "PROOF DOES NOT HOLD (arm1=$a1 arm2=$a2 arm3=$a3 arm4=$a4 arm5=$a5 arm6=$a6)."
exit 1
