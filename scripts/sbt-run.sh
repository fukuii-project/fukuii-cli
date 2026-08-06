#!/bin/bash
# Run an sbt task with its output in a log file, and refuse to report a success
# sbt did not actually earn.
#
# Usage: scripts/sbt-run.sh <log-name> <sbt-task> [<sbt-task> ...]
#   prints exactly one line: DONE log=<path> exit=<N>
#   exit = sbt's own code, EXCEPT:
#     97 = hollow success (see guard 3) -- sbt said 0, nothing was built
#
# WHY A WRAPPER AT ALL. Long, noisy output streamed live through an agent's own
# tool call is what froze this project's host machine during a compile sweep --
# a machine-level failure, not a slow command. All output goes to a file and
# stdout carries one line. That rationale is the house background-execution
# protocol's, and is not restated here.
#
# WHY NOT `set -e`. This script must SURVIVE sbt failing: it has to capture the
# real exit code, run guard 3, write the footer, and print the completion line.
# `set -e` would abort at the failure and none of that would happen. The cost of
# dropping -e is that nothing catches a failed `cd`, so the one `cd` below is
# guarded explicitly.
#
# ─────────────── The guards, and what each is anchored to ───────────────
#
# INHERITED AND UNVERIFIED. Guards 1 and 3 are ported from this project's prior
# implementation, where both were reproduced on 2026-07-16 against a
# MULTI-MODULE tree this repository does not have. Neither has been reproduced
# here. What is verified here is that each guard's SUBJECT exists: sbt 2.0.4 is
# the pinned launcher, `lsof` and `ps` are present, and target/out carries the
# sbt 2.x content-addressed layout guard 3 reads. The proof beside this script
# drives both guards through stubs, which demonstrates the guard logic, never
# the underlying sbt behavior.
#
# GUARD 1 -- a stale detached sbt server answers without rebuilding.
#   sbt's persistent server does not reload build.sbt / project/*.scala /
#   project/build.properties on its own. A server started before the last
#   build-definition edit is serving a stale settings graph and will answer
#   clean/compile with a fast [success] having recompiled nothing. Remedy: if
#   the registered server started before the newest build-definition file, kill
#   it so the invocation that follows reloads.
#
# GUARD 2 -- DEFERRED, and the trigger is precise.
#   The prior implementation rejected `"project <id>" "clean" "compile"`, which
#   silently runs only the project switch and still exits 0. VERIFIED HERE
#   2026-08-06 rather than assumed: build.sbt declares exactly one project,
#   `lazy val root = (project in file("."))`. With one project there is no
#   `project <id>` switch to misuse, so the guard would have no reachable
#   trigger and would be dead config from the day it landed.
#   TRIGGER TO ADD IT: the build declares a second project.
#
# GUARD 3 -- hollow success.
#   `clean` always invalidates cached compile state, so a `clean` followed by a
#   real compile can never legitimately leave the compile-output paths
#   untouched. If the task list contains `clean`, sbt exits 0, and those paths
#   did not advance, this reports 97 instead of 0.
#
#   TWO SCOPING CORRECTIONS AGAINST THE PORTED VERSION, both measured here:
#
#   (a) NEVER scan the whole target/ tree. target/global-logging and every
#       project's streams/update/meta directories are touched by ANY sbt
#       invocation, including one that did nothing. Scanning broadly produced a
#       false "something changed" reading that masked a real hollow run.
#
#   (b) EXCLUDE THE METABUILD, which the ported globs did not. Measured in this
#       repository: target/out/jvm/scala-3.8.4/ holds `fukuii-cli-build` and
#       `fukuii-cli-build-build` -- sbt's metabuild, which runs on Scala Next
#       and is matched by the ported `target/out/*/scala-*/*/classes` pattern.
#       `clean` does not clean the metabuild, so counting it can only make the
#       guard MORE likely to see an advance: a false green, which is the one
#       direction that matters. Metabuild projects are named `<root>-build`, so
#       they are excluded by name below.
#
#   The ported `modules/*/target/scala-*/...` half is DROPPED: measured here it
#   matches nothing, and a dead path in a guard reads as coverage.
set -uo pipefail

if [ "$#" -lt 2 ]; then
  printf 'Usage: %s <log-name> <sbt-task> [<sbt-task> ...]\n' "$(basename "$0")" >&2
  exit 2
fi

REPO_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 2
cd "$REPO_ROOT" || { printf 'ERROR: cannot enter %s\n' "$REPO_ROOT" >&2; exit 2; }

LOG_DIR="$REPO_ROOT/.local/logs"
mkdir -p "$LOG_DIR" || exit 2
LOG_NAME=$1
shift
LOG_FILE="$LOG_DIR/${LOG_NAME}.log"

# Join several task arguments into one semicolon-separated command string.
if [ "$#" -gt 1 ]; then
  SBT_CMD=$1
  shift
  for task in "$@"; do SBT_CMD="${SBT_CMD}; ${task}"; done
  set -- "$SBT_CMD"
fi

{
  printf '## sbt-run.sh started %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '## tasks: %s\n\n' "$*"
} >"$LOG_FILE"

# ─────────────────────────── Guard 1 ───────────────────────────
ACTIVE_JSON="$REPO_ROOT/project/target/active.json"
if [ -f "$ACTIVE_JSON" ] && command -v lsof >/dev/null 2>&1; then
  SOCK_PATH=$(sed -n 's#.*"uri":"local://\(.*\)"}.*#\1#p' "$ACTIVE_JSON" 2>/dev/null)
  if [ -n "$SOCK_PATH" ]; then
    SERVER_PID=$(lsof -U 2>/dev/null | awk -v s="$SOCK_PATH" '$0 ~ s && $0 ~ /LISTEN/ {print $2; exit}')
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
      SERVER_START=$(date -d "$(ps -o lstart= -p "$SERVER_PID" 2>/dev/null)" +%s 2>/dev/null || true)
      NEWEST_DEF=$(find "$REPO_ROOT/build.sbt" "$REPO_ROOT/project" -maxdepth 1 \
        \( -name '*.scala' -o -name '*.sbt' -o -name 'build.properties' \) \
        -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1)
      if [ -n "$SERVER_START" ] && [ -n "$NEWEST_DEF" ] && [ "$SERVER_START" -lt "$NEWEST_DEF" ] 2>/dev/null; then
        {
          printf '## stale-server guard: pid %s started %s, older than the newest\n' "$SERVER_PID" "$SERVER_START"
          printf '## build-definition file (%s) -- killing so sbt reloads.\n\n' "$NEWEST_DEF"
        } >>"$LOG_FILE"
        kill "$SERVER_PID" 2>/dev/null
        WAITED=0
        while kill -0 "$SERVER_PID" 2>/dev/null && [ "$WAITED" -lt 20 ]; do
          sleep 0.2
          WAITED=$((WAITED + 1))
        done
        kill -9 "$SERVER_PID" 2>/dev/null
        rm -f "$SOCK_PATH" "$ACTIVE_JSON"
      fi
    fi
  fi
fi

# ─────────────────────────── Guard 3 ───────────────────────────
# Newest mtime across the REAL compile-output paths, metabuild excluded.
compile_output_newest_epoch() {
  local dirs=() d
  for d in "$REPO_ROOT"/target/out/*/scala-*/*/classes \
    "$REPO_ROOT"/target/out/*/scala-*/*/test-classes \
    "$REPO_ROOT"/target/out/*/scala-*/*/zinc \
    "$REPO_ROOT"/target/out/*/scala-*/*/test-zinc; do
    # An unmatched glob passes through literally in bash, so test existence.
    [ -e "$d" ] || continue
    # Metabuild projects are named <root>-build; `clean` never cleans them, so
    # counting them can only produce a false "something advanced".
    case "$d" in *-build/*) continue ;; esac
    dirs+=("$d")
  done
  if [ "${#dirs[@]}" -eq 0 ]; then printf '0'; return; fi
  find "${dirs[@]}" -printf '%T@\n' 2>/dev/null | sort -rn | head -1 | cut -d. -f1
}

HAS_CLEAN=0
if printf '%s' "$*" | grep -qE '(^|;)[[:space:]]*([A-Za-z0-9_.-]+/)*clean[[:space:]]*(;|$)'; then
  HAS_CLEAN=1
fi

BASELINE=0
if [ "$HAS_CLEAN" -eq 1 ]; then
  BASELINE=$(compile_output_newest_epoch)
  BASELINE=${BASELINE:-0}
fi

sbt -no-colors -Dsbt.supershell=false "$@" >>"$LOG_FILE" 2>&1
SBT_EXIT=$?

if [ "$HAS_CLEAN" -eq 1 ] && [ "$SBT_EXIT" -eq 0 ]; then
  AFTER=$(compile_output_newest_epoch)
  AFTER=${AFTER:-0}
  if [ "$AFTER" -le "$BASELINE" ]; then
    {
      printf '\n## HOLLOW SUCCESS: the task list included a clean task and sbt exited\n'
      printf '## 0, but the real compile-output paths never advanced (baseline=%s\n' "$BASELINE"
      printf '## after=%s).\n' "$AFTER"
      printf '## A clean followed by a real compile always writes something new there.\n'
      printf '## Treat this as a stale server or a swallowed command, NOT as a pass.\n'
    } >>"$LOG_FILE"
    SBT_EXIT=97
  fi
fi

{
  printf '\n## sbt-run.sh finished %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'EXIT CODE: %d\n' "$SBT_EXIT"
} >>"$LOG_FILE"

printf 'DONE log=%s exit=%d\n' "$LOG_FILE" "$SBT_EXIT"
exit "$SBT_EXIT"
