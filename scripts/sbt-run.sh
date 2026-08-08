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
# GUARD 2 -- reject `project <id>` outright, rather than detect its misuse.
#   The failure is an sbt `project <id>` selector swallowing the tasks chained
#   after it: `"project foo" "clean" "compile"` runs only the switch and still
#   exits 0. The ported version DETECTED that shape, which meant it could only
#   fire once a second project existed to switch between -- dead config in a
#   single-project build, and a guard that reports the mistake after it has
#   already been made.
#
#   Refusing the form instead binds today, with one project, and keeps binding
#   at twenty. Module-scoped `<mod>/<task>` syntax reaches every project, needs
#   no switch, and does not exhibit the failure at all, so nothing legitimate
#   is lost -- AGENTS.md § Commands already tells readers to use it.
#
#   ALL occurrences are refused, including a trailing bare `project foo`, which
#   the ported version allowed. Through a batch wrapper that switch is a no-op:
#   the process exits and the selection is gone, so the run does nothing and
#   exits 0 -- a hollow success in its own right, which is the thing this
#   wrapper exists to stop reporting.
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

# ─────────────────────────── Guard 2 ───────────────────────────
# Checked before sbt is invoked at all, so the unsafe form never runs.
REJECT=0
IFS=';' read -ra TOKENS <<<"$*"
for tok in "${TOKENS[@]}"; do
  trimmed=$(printf '%s' "$tok" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
  if printf '%s' "$trimmed" | grep -qE '^project[[:space:]]+[^[:space:]]+'; then
    REJECT=1
  fi
done

if [ "$REJECT" -eq 1 ]; then
  {
    printf '## REJECTED before sbt ran: the task list contains an sbt\n'
    printf '## project-id selector. Tasks chained after one are silently\n'
    printf '## discarded while sbt still exits 0, and a bare selector through a\n'
    printf '## batch invocation does nothing at all -- either way the run\n'
    printf '## reports success having built nothing.\n'
    printf '## Use module-scoped syntax instead: <mod>/<task>, e.g. foo/clean,\n'
    printf '## foo/compile, foo/Test/compile. It reaches every project, needs no\n'
    printf '## switch, and does not exhibit this failure.\n'
  } >>"$LOG_FILE"
  printf 'REJECTED log=%s exit=3 — project-id selector in the task list; use <mod>/<task>\n' "$LOG_FILE" >&2
  exit 3
fi

# ─────────────────────────── Guard 1 ───────────────────────────
# THE VALUE READ OUT OF active.json SELECTS A `kill -9` TARGET, so it is treated
# as untrusted input rather than as a path. active.json is machine-generated and
# gitignored: nothing reviews it, and no diff shows it changing.
#
# Three defects, all measured 2026-08-07 against real `lsof -U` output on this
# machine, and the first is a plain bug with no attacker at all:
#
#   * `awk '$0 ~ s'` compiles the value as a DYNAMIC REGEX over the whole line.
#     A real socket path contains `.`, a regex metacharacter, so the match was
#     already looser than intended for every user. Given `"."` it matched
#     pipewire; `"dbus"` matched dbus-daemon; `"^"` matched pipewire. A bystander
#     kill was reproduced end to end through the unmodified script.
#   * The age comparison does not save it. systemd, pipewire and dbus-daemon all
#     started before the newest build-definition file and so all PASS the staleness
#     test -- the genuine java server was the one process the age gate exempted.
#   * A SUBSTRING match is not the fix either: `index($0, s)` still matches a
#     line whose NAME field merely CONTAINS the socket path, so a decoy at
#     `<sock>.bak` selects the wrong process. Measured, not reasoned.
#
# So the value is validated before use, matched as a whole FIELD rather than as
# a pattern, and the selected pid is confirmed to be a JVM before anything is
# signalled. Every rejection SKIPS the guard, which is the safe direction: the
# cost of not killing a stale server is one confusing rebuild, and the cost of
# killing the wrong process is unbounded.
ACTIVE_JSON="$REPO_ROOT/project/target/active.json"
if [ -f "$ACTIVE_JSON" ] && command -v lsof >/dev/null 2>&1; then
  # `[^"]*` bounds the capture to one JSON string value. The former `\(.*\)"}`
  # was greedy, so a second `"uri":` entry later in the file was swallowed whole
  # into the captured "path".
  SOCK_PATH=$(sed -n 's#.*"uri":"local://\([^"]*\)".*#\1#p' "$ACTIVE_JSON" 2>/dev/null | head -1)

  # Absolute, no whitespace, and no shell or regex metacharacter. A real sbt
  # server socket path satisfies this; a regex, a relative path, or anything
  # carrying an expansion character does not, and is refused rather than
  # sanitized. A path this rejects means the guard skips -- never that the
  # value is used anyway in some reduced form.
  case $SOCK_PATH in
    /*) ;;
    *) SOCK_PATH="" ;;
  esac
  if [ -n "$SOCK_PATH" ] && printf '%s' "$SOCK_PATH" | grep -qE '[^A-Za-z0-9._/@+:=-]'; then
    {
      printf '## stale-server guard SKIPPED: the socket path in active.json\n'
      printf '## carries a character outside the permitted set, so it is not\n'
      printf '## being used to select a process to kill.\n\n'
    } >>"$LOG_FILE"
    SOCK_PATH=""
  fi
  # It must be an actual socket. A regular file, a directory or a dangling path
  # is not a listening sbt server, and `-S` costs nothing to ask.
  if [ -n "$SOCK_PATH" ] && [ ! -S "$SOCK_PATH" ]; then
    SOCK_PATH=""
  fi

  if [ -n "$SOCK_PATH" ]; then
    # WHOLE-FIELD EQUALITY, never a regex and never a substring. lsof prints the
    # socket path as its own whitespace-delimited NAME field, and the validation
    # above guarantees the value has no whitespace, so field equality is exact.
    #
    # TWO gates above make it exact, not one, and the second is easy to remove by
    # accident. awk's `==` compares NUMERICALLY when both sides look like numbers,
    # so a value that is a bare number would match a numerically equal field
    # rather than an identical one. The `case $SOCK_PATH in /*)` test is what
    # structurally prevents that: a leading `/` cannot be a numeric string.
    # Measured on this machine's mawk 1.3.4 -- with s="42" the field `042`
    # MATCHES, and with s="/42" the field `42` does not. So loosening the
    # absolute-path requirement silently reopens a comparison this comment
    # otherwise credits entirely to the character-class check.
    SERVER_PID=$(lsof -U 2>/dev/null | awk -v s="$SOCK_PATH" \
      '$0 ~ /LISTEN/ { for (i = 1; i <= NF; i++) if ($i == s) { print $2; exit } }')

    # The pid must be a JVM. This is the check that makes a mis-selected target
    # harmless rather than fatal: every bystander reproduced above -- systemd,
    # pipewire, dbus-daemon -- fails it, and the sbt server is the process it
    # was written to admit.
    if [ -n "$SERVER_PID" ]; then
      SERVER_COMM=$(ps -o comm= -p "$SERVER_PID" 2>/dev/null | tr -d '[:space:]')
      case $SERVER_COMM in
        java | sbt | sbtn) ;;
        *)
          {
            printf '## stale-server guard SKIPPED: pid %s is `%s`, not a JVM.\n' \
              "$SERVER_PID" "${SERVER_COMM:-unknown}"
            printf '## Refusing to signal a process this guard did not identify\n'
            printf '## as an sbt server.\n\n'
          } >>"$LOG_FILE"
          SERVER_PID=""
          ;;
      esac
    fi

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
        # Reachable only past every check above, so `$SOCK_PATH` here is an
        # absolute, metacharacter-free path that WAS a socket held by the JVM
        # just killed. Unvalidated, this was an arbitrary-path unlink driven by
        # a gitignored file.
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
