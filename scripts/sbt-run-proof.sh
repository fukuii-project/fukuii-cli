#!/bin/bash
# Prove that scripts/sbt-run.sh's guards can actually FIRE, and do not fire on
# an honest run.
#
# Usage: scripts/sbt-run-proof.sh [<wrapper-to-certify>]
#          exit 0 = proof holds
#
# THE TARGET IS A PARAMETER, and that is what makes this proof falsifiable. A
# control whose subject is hardcoded can only ever report on the current file,
# so "the arms pass" and "the arms cannot fail" are indistinguishable from the
# outside. Pointing it at a previous revision is how a new arm is shown to be a
# real regression test rather than a restatement of what the code already does:
#
#     git show <rev>:scripts/sbt-run.sh > /tmp/old.sh
#     scripts/sbt-run-proof.sh /tmp/old.sh     # the new arms MUST fail here
#
# It also keeps the proof off the artifact it certifies -- nothing here writes
# to scripts/sbt-run.sh, so an interrupted run cannot leave a defective wrapper
# behind.
#
# NO JVM RUNS HERE. Every arm builds a throwaway repo and puts a STUB `sbt` on
# PATH ahead of the real one. That is deliberate and it is the only way to
# calibrate guard 3 at all: the guard's whole subject is "sbt exited 0 having
# built nothing", which a real sbt will not do on demand. Taking the wrapped
# command as a PATH lookup is what makes the real guard code reachable with a
# controllable subject -- the same shape the house standard prescribes for
# calibrating a script that drives another program.
#
# WHAT THIS PROVES AND WHAT IT DOES NOT. It proves the guard LOGIC fires on the
# condition it claims and stays quiet otherwise. It does NOT prove sbt exhibits
# the underlying failures -- those were reproduced in this project's prior
# implementation on 2026-07-16 against a multi-module tree, are inherited here,
# and are marked as such in the wrapper's own header.
#
# NOTHING HERE TOUCHES THIS REPOSITORY, and no arm kills a process it did not
# start itself.
set -uo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd) || exit 1
WRAPPER=${1:-$REPO/scripts/sbt-run.sh}
[ -f "$WRAPPER" ] || { echo "missing: $WRAPPER"; exit 1; }
echo "certifying: $WRAPPER"

# A throwaway repo with the wrapper installed at the same depth it expects, a
# build definition, and the sbt 2.x compile-output layout measured in this repo.
build_tree() { # build_tree -> prints tmpdir
  local tmp=$1
  mkdir -p "$tmp/scripts" "$tmp/project" "$tmp/bin"
  cp "$WRAPPER" "$tmp/scripts/sbt-run.sh"
  printf 'ThisBuild / scalaVersion := "3.3.8"\n' >"$tmp/build.sbt"
  printf 'sbt.version=2.0.4\n' >"$tmp/project/build.properties"
  # Real project output, plus a metabuild dir under a different Scala version --
  # the exact shape measured in this repository.
  mkdir -p "$tmp/target/out/jvm/scala-3.3.8/proj/classes"
  mkdir -p "$tmp/target/out/jvm/scala-3.8.4/proj-build/classes"
  touch -d '2020-01-01 00:00:00' "$tmp/target/out/jvm/scala-3.3.8/proj/classes"
  touch -d '2020-01-01 00:00:00' "$tmp/target/out/jvm/scala-3.8.4/proj-build/classes"
}

# Stub sbt. MODE=honest touches the real project output; MODE=hollow exits 0
# having touched nothing; MODE=metabuild touches ONLY the metabuild.
make_stub_sbt() { # make_stub_sbt <tmp> <mode>
  local tmp=$1 mode=$2
  cat >"$tmp/bin/sbt" <<STUB
#!/bin/bash
case "$mode" in
  honest)   touch "$tmp/target/out/jvm/scala-3.3.8/proj/classes" ;;
  metabuild) touch "$tmp/target/out/jvm/scala-3.8.4/proj-build/classes" ;;
  hollow)   : ;;
esac
exit 0
STUB
  chmod +x "$tmp/bin/sbt"
}

arm() { # arm <label> <mode> <tasks...> ; expected exit in WANT
  local label=$1 mode=$2 want=$3; shift 3
  local tmp rc out
  tmp=$(mktemp -d) || return 1
  build_tree "$tmp"
  make_stub_sbt "$tmp" "$mode"
  # Ensure the touch lands strictly after the 2020 baseline.
  out=$(PATH="$tmp/bin:$PATH" bash "$tmp/scripts/sbt-run.sh" proof "$@" 2>&1)
  rc=$?
  echo "--- $label"
  echo "    stub sbt mode : $mode"
  echo "    tasks         : $*"
  echo "    $out"
  echo "    exit: $rc (expected $want)"
  [ "$rc" != "$want" ] && echo "    *** UNEXPECTED ***"
  rm -rf "$tmp"
  [ "$rc" = "$want" ]
}

echo "###### GUARD 3 ######"
echo
echo "--- ARM 1: clean+compile, sbt built nothing. MUST be 97, not 0."
arm "hollow success" hollow 97 clean compile; g3a=$?

echo
echo "--- ARM 2: clean+compile, sbt really built. MUST be 0."
arm "honest build" honest 0 clean compile; g3b=$?

echo
echo "--- ARM 3: no \`clean\` in the task list. Guard must NOT fire."
echo "    An incremental no-op is legitimate and must not be reported as hollow."
arm "compile only, nothing built" hollow 0 compile; g3c=$?

echo
echo "--- ARM 4: only the METABUILD advanced. MUST still be 97."
echo "    This is the ported globs' defect: they counted target/out/*/scala-*/"
echo "    *-build/ as project output, so a metabuild touch read as a real build."
arm "metabuild-only advance" metabuild 97 clean compile; g3d=$?

echo
echo "###### GUARD 2 ######"
echo "    Rejection, not detection: the unsafe form must be refused BEFORE sbt"
echo "    is invoked, so it cannot run at all. Exit 3 is its own code."
echo
echo "--- ARM 7: a project-id selector with tasks chained after it. MUST be 3."
arm "project foo; clean; compile" honest 3 "project foo" clean compile; g2a=$?

echo
echo "--- ARM 8: a BARE trailing project-id selector. MUST also be 3."
echo "    The ported guard allowed this. Through a batch wrapper the switch is"
echo "    lost when the process exits, so the run does nothing and exits 0."
arm "clean; project foo" honest 3 clean "project foo"; g2b=$?

echo
echo "--- ARM 9: module-scoped syntax. MUST NOT be rejected."
echo "    The remedy the rejection message names has to actually work, or the"
echo "    guard refuses every route to the same outcome."
arm "foo/clean; foo/compile" honest 0 foo/clean foo/compile; g2c=$?

echo
echo "--- ARM 10: a task merely CONTAINING the word project. MUST NOT reject."
echo "    Near-miss control: a rejection keyed on a substring would fire here."
arm "projectInfo" honest 0 projectInfo; g2d=$?

echo
echo "###### GUARD 1 ######"
echo "    Every process here is one this proof starts itself, and lsof is"
echo "    stubbed to report only those pids, so no process on this machine is"
echo "    reachable."
echo
echo "    WHY THE ARMS BELOW CARRY A SECOND PROCESS. Arms 5 and 6 alone used a"
echo "    single candidate whose lsof NAME field ALWAYS matched the socket path"
echo "    exactly, so regex-versus-substring-versus-exact-match was a"
echo "    distinction the fixture could not express -- and the proof printed"
echo "    PROOF HOLDS identically for the vulnerable code and for two candidate"
echo "    fixes, one of which was still exploitable. A bystander that MUST"
echo "    survive is what makes the target selection testable at all."
echo
echo "    The stand-ins are named by copying a harmless binary, because the"
echo "    guard refuses to signal a process that is not a JVM; and the socket"
echo "    is a real bound AF_UNIX socket, because the guard requires -S. A"
echo "    touched regular file passed the old code and does not pass this one,"
echo "    so a proof still using one would certify a path the guard never takes."

# A real unix socket, bound and abandoned. The filesystem entry outlives the
# process that bound it, which is exactly the stale-socket shape guard 1 meets.
make_socket() { # make_socket <path>
  python3 -c 'import socket, sys
sock = socket.socket(socket.AF_UNIX)
sock.bind(sys.argv[1])' "$1" 2>/dev/null
}

# A disposable process reporting a chosen name in `ps -o comm=`. Verified:
# a copy of /bin/sleep at bin/java reports comm=java.
#
# THE REDIRECTION IS LOAD-BEARING, not tidiness. This runs inside `$(...)`, and
# command substitution reads until the pipe CLOSES -- not until the foreground
# command exits. A background child inherits that pipe, so without the
# redirection `$(start_standin ...)` blocks for the child's full lifetime. It
# did: the proof hung at ARM 5 and was killed at 300s having printed nothing.
start_standin() { # start_standin <tmp> <name> -> prints pid
  cp /bin/sleep "$1/bin/$2" 2>/dev/null || return 1
  "$1/bin/$2" 300 >/dev/null 2>&1 &
  echo $!
}

alive_of() { if kill -0 "$1" 2>/dev/null; then echo yes; else echo no; fi; }

# guard1_arm <label> <age> <uri-override> <sock-name> <server-name> \
#            <decoy-name> <want-server> <want-decoy>
#   <uri-override>  empty = the real socket path; otherwise written verbatim
#                   into active.json in its place
#   <sock-name>     the socket's basename, so a path carrying a metacharacter
#                   can be exercised while still being a REAL socket
#   <server-name>   process name for the candidate AT the socket path
#   <decoy-name>    empty = no decoy; otherwise a second process listed FIRST
#                   in lsof output, at <sock>.bak -- a NAME field that CONTAINS
#                   the socket path without equalling it
#   <want-server> / <want-decoy>   yes | no | -- (absent)
guard1_arm() {
  local label=$1 age=$2 uri=$3 sockname=$4 sname=$5 dname=$6 want_s=$7 want_d=$8
  local tmp sock spid dpid alive_s alive_d ok=0
  tmp=$(mktemp -d) || return 1
  build_tree "$tmp"
  make_stub_sbt "$tmp" honest
  mkdir -p "$tmp/project/target"

  sock="$tmp/$sockname"
  if ! make_socket "$sock"; then
    echo "--- $label"
    echo "    *** could not create a unix socket; arm did not run ***"
    rm -rf "$tmp"
    return 1
  fi
  printf '{"uri":"local://%s"}\n' "${uri:-$sock}" >"$tmp/project/target/active.json"

  spid=$(start_standin "$tmp" "$sname") || return 1
  dpid=""
  if [ -n "$dname" ]; then
    dpid=$(start_standin "$tmp" "$dname") || return 1
  fi

  # The decoy is listed FIRST. awk exits on its first match, so a regex or a
  # substring test selects the decoy and a whole-field test skips past it --
  # the ordering is what makes the two outcomes distinguishable.
  {
    echo '#!/bin/bash'
    [ -n "$dpid" ] && echo "echo \"$dname $dpid u unix 0x0 0t0 LISTEN $sock.bak type=STREAM\""
    echo "echo \"$sname $spid u unix 0x0 0t0 LISTEN $sock type=STREAM\""
  } >"$tmp/bin/lsof"
  chmod +x "$tmp/bin/lsof"

  # Age EVERY build-definition file the guard looks at, not just build.sbt.
  # An earlier draft aged build.sbt alone and left project/build.properties at
  # fixture-creation time -- within a second of the stand-in server -- so the
  # "fresh server" arm failed on sub-second timing while the guard was behaving
  # correctly. A control that fails for an incidental reason is a placebo for
  # the class it claims to cover, and this one nearly reported a working guard
  # as broken.
  touch -d "$age" "$tmp/build.sbt" "$tmp/project/build.properties"
  PATH="$tmp/bin:$PATH" bash "$tmp/scripts/sbt-run.sh" proof compile >/dev/null 2>&1
  sleep 0.3

  alive_s=$(alive_of "$spid")
  alive_d="--"
  [ -n "$dpid" ] && alive_d=$(alive_of "$dpid")

  kill -9 "$spid" 2>/dev/null
  [ -n "$dpid" ] && kill -9 "$dpid" 2>/dev/null
  wait "$spid" 2>/dev/null
  [ -n "$dpid" ] && wait "$dpid" 2>/dev/null
  rm -rf "$tmp"

  echo "--- $label"
  echo "    build-def mtime : $age"
  echo "    active.json uri : ${uri:-<the real socket path>}"
  echo "    candidate at the socket path (\`$sname\`) alive: $alive_s (expected $want_s)"
  [ "$alive_s" = "$want_s" ] || ok=1
  if [ -n "$dname" ]; then
    echo "    BYSTANDER at <sock>.bak (\`$dname\`)     alive: $alive_d (expected $want_d)"
    [ "$alive_d" = "$want_d" ] || ok=1
  fi
  [ "$ok" = 0 ] && echo "    ok" || echo "    *** UNEXPECTED ***"
  return "$ok"
}

echo
echo "--- ARM 5: build definition NEWER than the server. MUST kill it."
guard1_arm "stale server" "now + 60 seconds" "" sbtserver.sock java "" no --
g1a=$?

echo
echo "--- ARM 6: build definition OLDER than the server. MUST leave it alone."
guard1_arm "fresh server" "2020-01-01 00:00:00" "" sbtserver.sock java "" yes --
g1b=$?

echo
echo "--- ARM 11: a BYSTANDER whose lsof NAME merely CONTAINS the socket path."
echo "    <sock>.bak is listed first. A regex match (\$0 ~ s) selects it, and so"
echo "    does a substring match (index(\$0, s)) -- both kill the bystander and"
echo "    leave the real stale server running. Only whole-FIELD equality skips"
echo "    it. This arm is the one that separates the fix from the near-fixes."
guard1_arm "substring decoy, real server behind it" "now + 60 seconds" "" \
  sbtserver.sock java java-decoy no yes
g1c=$?

echo
echo "--- ARM 12: active.json carries a REGEX instead of a path. NOTHING dies."
echo "    \`.\` matched pipewire on this machine through the unmodified script;"
echo "    \`dbus\` matched dbus-daemon and \`^\` matched pipewire again. It is not"
echo "    an absolute path, so the guard now refuses it and skips."
guard1_arm "regex in active.json" "now + 60 seconds" "." \
  sbtserver.sock java java-decoy yes yes
g1d=$?

echo
echo "--- ARM 13: a REAL socket whose PATH carries a regex metacharacter."
echo "    Absolute, existing, and a genuine socket -- so only the character-set"
echo "    check can reject it. Compiled as a regex, \`.*\` matches every line and"
echo "    selects whichever process lsof happens to print first."
guard1_arm "metacharacter in the socket path" "now + 60 seconds" "" \
  'a.*b.sock' java java-decoy yes yes
g1e=$?

echo
echo "--- ARM 14: the process at the exact socket path is NOT a JVM."
echo "    This is the reproduced bystander class: systemd, pipewire and"
echo "    dbus-daemon all pass the age test, because the age test exempted the"
echo "    genuine java server rather than the bystanders."
guard1_arm "non-JVM at the exact socket path" "now + 60 seconds" "" \
  sbtserver.sock pipewire "" yes --
g1f=$?

echo
if [ "$g3a" = 0 ] && [ "$g3b" = 0 ] && [ "$g3c" = 0 ] && [ "$g3d" = 0 ] \
  && [ "$g2a" = 0 ] && [ "$g2b" = 0 ] && [ "$g2c" = 0 ] && [ "$g2d" = 0 ] \
  && [ "$g1a" = 0 ] && [ "$g1b" = 0 ] && [ "$g1c" = 0 ] && [ "$g1d" = 0 ] \
  && [ "$g1e" = 0 ] && [ "$g1f" = 0 ]; then
  echo "PROOF HOLDS: guard 3 reports 97 on a hollow run and on a metabuild-only"
  echo "advance and stays silent otherwise; guard 2 refuses both project-id"
  echo "forms and passes module-scoped syntax and a near-miss task name;"
  echo "guard 1 kills a stale server, spares a fresh one, spares a bystander"
  echo "whose name merely contains the socket path, refuses a regex and a"
  echo "metacharacter-carrying path outright, and refuses to signal a process"
  echo "that is not a JVM."
  exit 0
fi
echo "PROOF DOES NOT HOLD (g3: $g3a $g3b $g3c $g3d / g2: $g2a $g2b $g2c $g2d"
echo "                     / g1: $g1a $g1b $g1c $g1d $g1e $g1f)"
exit 1
