#!/bin/bash
# Prove that scripts/sbt-run.sh's guards can actually FIRE, and do not fire on
# an honest run.
#
# Usage: scripts/sbt-run-proof.sh          exit 0 = proof holds
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
WRAPPER="$REPO/scripts/sbt-run.sh"
[ -f "$WRAPPER" ] || { echo "missing: $WRAPPER"; exit 1; }

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
echo "###### GUARD 1 ######"
echo "    A disposable process this proof starts itself stands in for the sbt"
echo "    server. lsof is stubbed to report THAT pid and no other, so the only"
echo "    process the guard can reach is one this script owns."
guard1_arm() { # guard1_arm <label> <build-def-age> <want-killed>
  local label=$1 age=$2 want=$3 tmp pid rc alive
  tmp=$(mktemp -d) || return 1
  build_tree "$tmp"
  make_stub_sbt "$tmp" honest
  mkdir -p "$tmp/project/target"
  local sock="$tmp/sbtserver.sock"
  printf '{"uri":"local://%s"}\n' "$sock" >"$tmp/project/target/active.json"
  touch "$sock"
  sleep 120 &
  pid=$!
  cat >"$tmp/bin/lsof" <<LSOF
#!/bin/bash
echo "stub $pid u unix 0x0 0t0 LISTEN $sock type=STREAM"
LSOF
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
  if kill -0 "$pid" 2>/dev/null; then alive=yes; else alive=no; fi
  kill -9 "$pid" 2>/dev/null
  wait "$pid" 2>/dev/null
  rm -rf "$tmp"
  echo "--- $label"
  echo "    build.sbt mtime : $age"
  echo "    stand-in server still alive after run: $alive (expected $want)"
  [ "$alive" = "$want" ]
}

echo
echo "--- ARM 5: build definition NEWER than the server. MUST kill it."
guard1_arm "stale server" "now + 60 seconds" no; g1a=$?

echo
echo "--- ARM 6: build definition OLDER than the server. MUST leave it alone."
guard1_arm "fresh server" "2020-01-01 00:00:00" yes; g1b=$?

echo
if [ "$g3a" = 0 ] && [ "$g3b" = 0 ] && [ "$g3c" = 0 ] && [ "$g3d" = 0 ] && [ "$g1a" = 0 ] && [ "$g1b" = 0 ]; then
  echo "PROOF HOLDS: guard 3 reports 97 on a hollow run and on a metabuild-only"
  echo "advance, stays silent on an honest build and on a clean-less task list;"
  echo "guard 1 kills a stale server and spares a fresh one."
  exit 0
fi
echo "PROOF DOES NOT HOLD (g3: $g3a $g3b $g3c $g3d / g1: $g1a $g1b)"
exit 1
