# Consensus — the proof-of-stake family

**currency:** **every protocol fact in this file is inherited and unverified.**
Every chain identifier, opcode number, proposal number, precompile address,
header field name and set-membership claim below was carried over from this
project's prior implementation, and **not one of them has been checked against
its own improvement proposal, the consensus specifications, or a reference
client.** Treat each as a lead, never as a fact — `.claude/agents/forge.md` §
Provenance states the rule and why a document in this repository's own framework
layer can never be the authority for a value. The *shape* of the work here is
policy and does not expire; the values do not have that standing. Checked
2026-08-17.

**No frontmatter, and none is possible.** A file under `.claude/protocols/` does
not auto-load — Claude Code discovers `.claude/rules/`, not this directory — so
`paths:` here would do nothing. **Something has to reach this file by name**,
whether that is an import, a pointer a reader follows, or a task brief quoting
it. Nothing warns you when that stops happening.

**What reaches it is `.claude/agents/forge.md`**, which owns consensus for
every family and instructs its reader to open this file before acting on a
proof-of-stake change. That charter body loads on dispatch; this file does not.
**So a proof-of-stake consensus task that never opened this protocol is running
on recollection**, and the charter treats that as the same finding as the
protocol being absent.

**This is a family-fact protocol, not the deferred consensus-change protocol.**
The state-root litmus — the rule deciding whether a change is consensus at all —
is family-neutral, still lives in the charters, and its canonical home remains a
consensus-change protocol this repository does not have yet. Do not read this
file as having closed that gap.

---

## This family is read FIRST, and that is a rule about every other family too

**Proof-of-stake Ethereum is where EVM development happens** — proposals land, get implemented and
get exercised here before anywhere else. So for anything the families share, this family's sources
are read before the others', and a design derived from a downstream family first inherits its lag.

**The reference clients, in order:** `ethereum/execution-specs` (the executable specification, above
every client), then `ethereum/go-ethereum` (largest production client), then `besu-eth/besu` (largest
production **JVM** client, whose shape transfers most directly here).

**`.claude/reference-corpus.md` § "Reading order" is the full statement**, including where the other
families sit. **This ordering does not weaken any family where that family is the authority** — it
governs the default reading path for shared behavior, which is most of the EVM.

## Membership — the family, not a fixed list of networks

Ethereum mainnet and the Sepolia testnet are today's members. **A proof-of-stake
network added later falls under this protocol without amending it.**

---

## Fork dispatch: this family activates at a timestamp

**Post-merge forks on these networks activate at a timestamp.** That is this
family's axis, and it is the fact this file exists to put in front of you before
a dispatch site is written.

**A pre-merge fork on this family's own network is not on this family's axis.**
Ethereum mainnet ran proof-of-work until the Merge, so a fork before it
dispatches on the proof-of-work family's axis, a block number, not a
timestamp. Byzantium is the checked instance — Ethereum mainnet at block
4,370,000, per EIP-609 and this repository's own built dispatch entry, unlike
the rest of this file's header disclaimer. The network's membership here is a
fact about today; it says nothing about a fork's own era.
`.claude/protocols/consensus-pow.md`'s Fork dispatch section states that axis
in full; it is not restated here, and a dispatch site for anything before the
Merge reads it instead of this one.

**Do not carry the axis across families.** Another family's protocol states its
own, and `.claude/agents/forge.md` § "Fork dispatch" carries the shared
hazard: why the wrong axis is a consensus bug rather than a style problem, the
overloaded-dispatch-name trap inherited from the prior implementation, and the
rule that each family's fork-gated definitions stay independently defined. Read
it there — it is not restated here, and it binds regardless of which family you
are in.

---

## Standing properties of this family

- **Chain ID 1 on mainnet, 11155111 on Sepolia.**
- **The EIP-1559 base fee is burned. It is never redirected to any address.**
  Whatever another family does with its base fee must never reach this code
  path.
- **There is no execution-layer block reward.** Validator rewards are a
  consensus-layer concern and out of the execution layer's scope entirely, so
  any reward scheme on this path is zero or a no-op — not a smaller number.
- **Post-Cancun headers carry `withdrawalsRoot`, `excessBlobGas`, `blobGasUsed`
  and `parentBeaconBlockRoot`.** A header missing a required field is invalid,
  not merely lossy.
- **Blob transactions and withdrawals are present in this family.**
- **Never add mining or proof-of-work code paths to this family.**

**Reject any change that introduces another family's feature into this code
path**, however harmless the addition looks in isolation. What those features
are is stated by that family's own protocol, so read it rather than assuming the
contrast — the two families' properties are deliberately recorded once each,
under their own owner, rather than as a comparison table maintained in two
places.

---

## The fork sets, and the opcode question

**Prague adds no new opcode.** Its execution-layer change of that kind is the
EIP-7702 set-code transaction type, which is a transaction type rather than an
opcode. The rest of its execution-layer set: EIP-2537 (BLS12-381 precompiles at
`0x0b` through `0x11`), EIP-7623 (calldata floor gas), EIP-7691 (blob
throughput), EIP-7685 (execution requests), EIP-6110 (deposit processing),
EIP-7251 (maximum effective balance), EIP-7002 (execution-layer-triggered
validator exits).

**Osaka is Prague plus one opcode.** EIP-7939 (CLZ) at `0x1e` is **the only new
opcode in the set**. Alongside it: EIP-7823 and EIP-7883 (MODEXP input bounds and
gas), EIP-7951 (a P256VERIFY precompile at `0x100`), EIP-7918 (blob base-fee
reserve pricing), EIP-7892 (blob-parameter-only forks).

---

## The checkable negative — EIP-7594 is not an execution-layer fork gate

**EIP-7594 (PeerDAS) is a consensus and data-availability change. It is not
gated as an Osaka execution-layer fork.** Do not treat it as an opcode or
precompile EIP and do not add it to an execution-layer fork gate.

**This is the most easily lost kind of fact and the most expensive to
re-derive**, because nothing is missing when it is wrong — an extra entry in a
fork gate looks exactly like a correct one. It is also the one fact here whose
original check is reproducible: the prior implementation recorded looking for a
reference to it in go-ethereum's `params/config.go`, `core/vm/jump_table.go` and
`core/vm/eips.go`, and finding none in any of the three.

**The ref that check was made at was not recorded, so it is unverified like
everything else in this file.** Re-run it at a ref that cannot move before
relying on it, and note `.claude/rules/evidence-and-citation.md` §3: three files
in one client is not a corpus, so the true claim is about those files at that
ref, not about the ecosystem.

**An externally supplied value-shaped payload has exactly this shape, arriving
from outside.** `.claude/agents/forge.md` § "The payload this domain attracts
is value-shaped" is the handling rule; this section is what one looks like when
it is genuine, which is why the two are worth reading against each other.
