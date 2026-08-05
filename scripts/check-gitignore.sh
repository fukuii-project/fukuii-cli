#!/bin/bash
# Is .gitignore actually doing its job?
#
# Usage: scripts/check-gitignore.sh [repo-root]     exit 0 = every case matched
#
# This repository is PUBLIC and .gitignore is the gate between what stays local
# and what the world sees. That gate is edited over time -- node datadir
# patterns and test-fixture carve-outs are both already scheduled -- so it needs
# a check anyone can run, not one only its author can. That is why this lives in
# scripts/ and is committed rather than sitting in a gitignored scratch
# directory, which is where it began.
#
# ─── Rules this script exists to enforce, each written after it was violated ───
#
# 1. `git check-ignore --no-index -q` is the DECISION. Never `-v` as a condition:
#    -v exits 0 on a NEGATION match too, so `!.env.example` would read as proof a
#    file is ignored when it is precisely the opposite.
#
# 2. `--no-index` is required. Without it an already-TRACKED file reports
#    "not ignored" even when a pattern covers it.
#
# 3. Exit 128 is NOT "visible". check-ignore returns 128 for a path outside the
#    repo, a `../` escape, or an empty string. v1 of this script read every
#    non-zero as VISIBLE, so all 14 negative controls passed unconditionally on
#    error. Found by adversarial review, 2026-08-02.
#
# 4. EVERY ROOT-ANCHORED PATTERN GETS A NESTED PROBE. This is the big one. v1
#    tested the anchored key-material patterns only at the repo root — i.e. in
#    the two directions that CONFIRM the design, and never in the direction that
#    prices it. It reported PASS while `data/keystore/UTC--…` and
#    `data/mnemonic.txt` were both committable. A nested probe on `*.nodekey`
#    looked like coverage but exercises a GLOBAL glob, the one pattern class that
#    structurally cannot fail this way.
#
# 5. A NEGATION MUST BE PROVEN LOAD-BEARING, not assumed. `!*.tfvars.example` was
#    inert — `*.tfvars` never matches a name ending `.example` — so v1's control
#    on it would have passed against an EMPTY .gitignore. Each negation is now
#    tested differentially in a throwaway repo: delete the line, and the outcome
#    must change. If it does not, the negation is a placebo and so is any test
#    resting on it.
#
# 6. Negative controls must include SOURCE and TEST-FIXTURE paths, not only root
#    config files. Over-broad patterns fail silently in the data-loss direction,
#    and v1 had no probe that could see it.
#
# 7. bash, not zsh: in zsh an unquoted list does not word-split, which silently
#    collapses a whole pattern set into one argument and passes.
#
# Exit 0 = every case matched its expectation.
set -uo pipefail
# Root defaults to this repo. It is a PARAMETER so the countermeasure proof can
# run this same sweep against a throwaway repo holding a known-bad .gitignore --
# without that, proving the sweep can fail would mean mutating the live gate.
cd "${1:-$(dirname "$0")/..}" || exit 1

fail=0

check() {  # check <IGNORED|VISIBLE> <path>
  local want=$1 path=$2 got rc
  git check-ignore --no-index -q -- "$path"; rc=$?
  case $rc in
    0) got=IGNORED ;;
    1) got=VISIBLE ;;
    *) printf '  ERROR exit=%-3s %s  (rule 3: an error is not a result)\n' "$rc" "$path"
       fail=1; return ;;
  esac
  if [ "$got" = "$want" ]; then
    printf '  ok    %-8s %s\n' "$got" "$path"
  else
    printf '  FAIL  want=%-8s got=%-8s %s\n' "$want" "$got" "$path"
    fail=1
  fi
}

echo "=== MUST BE IGNORED — secrets & credentials ==="
for p in .env .env.local .env.production secrets/aws.json .secrets/x \
         server.pem private.key node.p12 cert.pfx store.jks app.keystore \
         id_rsa id_ecdsa id_ed25519 credentials.json service-account.json \
         db.credentials token.secret; do
  check IGNORED "$p"
done

echo "=== MUST BE IGNORED — developer-environment credentials (rule 6 gap, added) ==="
for p in .envrc .netrc _netrc .git-credentials apple.p8 putty.ppk \
         deploy_key my_rsa host_ecdsa signing_ed25519; do
  check IGNORED "$p"
done

echo "=== MUST BE IGNORED — registry auth ==="
for p in .npmrc .yarnrc .yarnrc.yml; do check IGNORED "$p"; done

echo "=== MUST BE IGNORED — node key material AT THE ROOT ==="
for p in keystore/UTC--2026-08-02T00-00-00.0Z--aabbcc wallet.json mnemonic.txt \
         node.key jwt.hex jwtsecret validator.nodekey; do
  check IGNORED "$p"
done

echo "=== RULE 4: the same artifacts at NESTED datadir depth ==="
echo "    A node writes these under whatever --datadir it is given. These probes"
echo "    are the ones v1 lacked, and they are what failed against v1's file."
for p in data/keystore/UTC--2026-08-02T00-00-00.0Z--aabbcc \
         data/wallet.json data/mnemonic.txt data/jwt.hex data/jwtsecret \
         data/node.key data/validator.nodekey \
         .fukuii/keystore/UTC--2026-08-02T00-00-00.0Z--deadbeef \
         var/lib/fukuii/mnemonic.txt \
         node1/data/keystore/UTC--2026-08-02T00-00-00.0Z--cafe \
         deep/nested/path/wallet.json; do
  check IGNORED "$p"
done

echo "=== MUST BE IGNORED — local working files ==="
# Probe paths are deliberately generic. `--no-index` does not require the file to
# exist, so naming real private documents would publish the shape of material the
# ignore rule exists to hold back — and would couple this test to a private layout.
for p in .local/ .local/nested/dir/file.md .local/another/x \
         SESSION_NOTES.md PLANNING.md SCRATCH.md notes.scratch.md plan.draft.md; do
  check IGNORED "$p"
done

echo "=== MUST BE IGNORED — Claude machine-local state (deny-list, not blanket) ==="
for p in .claude/settings.local.json .claude/worktrees/w1 \
         .claude/agent-memory-local/m .claude/launch.json \
         .claude/scheduled_tasks.json CLAUDE.local.md \
         .claude/agents/foo.local.md .claude/some-local/x; do
  check IGNORED "$p"
done

echo "=== MUST BE IGNORED — build output ==="
for p in target/x project/target/y modules/base/target/z out/a .bsp/sbt.json \
         .metals/m .bloop/b Foo.class; do
  check IGNORED "$p"
done

echo
echo "=== NEGATIVE CONTROLS — repo config, MUST stay visible ==="
for p in .env.example .env.production.example \
         README.md LICENSE .gitignore AGENTS.md CLAUDE.md \
         .claude/settings.json .claude/agents/reviewer.md \
         build.sbt version.sbt project/Dependencies.scala NOTICE; do
  check VISIBLE "$p"
done

echo "=== RULE 6: SOURCE and TEST-FIXTURE controls, MUST stay visible ==="
echo "    Over-broad patterns fail silently in the data-loss direction. The L10"
echo "    Ethereum reference-test harness is exactly where such fixtures land."
check VISIBLE "modules/keystore/src/main/scala/org/fukuii/keystore/KeyStore.scala"
check VISIBLE "modules/base/src/main/scala/org/fukuii/keystore/Wallet.scala"
check VISIBLE "modules/keystore/src/test/scala/org/fukuii/keystore/KeyStoreSpec.scala"
check VISIBLE "modules/rpc/src/main/resources/out/schema.json"
check VISIBLE "modules/evm/src/test/resources/vectors/state.json"

echo
echo "=== RULE 5: every negation proven LOAD-BEARING by differential ==="
echo "    Method: copy .gitignore to a throwaway repo, delete the negation line,"
echo "    re-check. If the outcome does not change, the negation is a placebo."
neg_differential() {  # neg_differential <negation-line> <probe-path>
  local negline=$1 probe=$2 tmp with without
  tmp=$(mktemp -d) || { echo "  FAIL  mktemp"; fail=1; return; }
  git -C "$tmp" init -q 2>/dev/null
  cp .gitignore "$tmp/.gitignore"
  git -C "$tmp" check-ignore --no-index -q -- "$probe"; with=$?
  grep -vxF "$negline" "$tmp/.gitignore" > "$tmp/.g2" && mv "$tmp/.g2" "$tmp/.gitignore"
  git -C "$tmp" check-ignore --no-index -q -- "$probe"; without=$?
  rm -rf "$tmp"
  if [ "$with" = 1 ] && [ "$without" = 0 ]; then
    printf '  ok    LOAD-BEARING   %-24s guards %s\n' "$negline" "$probe"
  else
    printf '  FAIL  PLACEBO        %-24s with=%s without=%s (must be 1 then 0)\n' \
           "$negline" "$with" "$without"
    fail=1
  fi
}
neg_differential '!.env.example'        '.env.example'
neg_differential '!.env.*.example'      '.env.production.example'
neg_differential '!.yarn/patches'       '.yarn/patches/p.diff'

echo
if [ "$fail" = 0 ]; then
  echo "RESULT: PASS — every case matched; negative controls and negations both discriminate."
else
  echo "RESULT: FAIL — see FAIL/ERROR lines above."
fi
exit "$fail"
