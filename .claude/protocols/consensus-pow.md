# Consensus — the proof-of-work family

**currency:** **every protocol fact in this file is inherited and unverified.**
Every chain identifier, opcode number, proposal number, address, emission figure
and set-membership claim below was carried over from this project's prior
implementation, and **not one of them has been checked against its own
specification, its improvement proposal, or a reference client.** Treat each as
a lead, never as a fact — `.claude/agents/forge.md` § Provenance states the
rule and why a document in this repository's own framework layer can never be
the authority for a value. The *shape* of the work here is policy and does not
expire; the values do not have that standing. Checked 2026-08-17.

**No frontmatter, and none is possible.** A file under `.claude/protocols/` does
not auto-load — Claude Code discovers `.claude/rules/`, not this directory — so
`paths:` here would do nothing. **Something has to reach this file by name**,
whether that is an import, a pointer a reader follows, or a task brief quoting
it. Nothing warns you when that stops happening.

**What reaches it is `.claude/agents/forge.md`**, which owns consensus for
every family and instructs its reader to open this file before acting on a
proof-of-work change. That charter body loads on dispatch; this file does not.
**So a proof-of-work consensus task that never opened this protocol is running
on recollection**, and the charter treats that as the same finding as the
protocol being absent.

**This is a family-fact protocol, not the deferred consensus-change protocol.**
The state-root litmus — the rule deciding whether a change is consensus at all —
is family-neutral, still lives in the charters, and its canonical home remains a
consensus-change protocol this repository does not have yet. Do not read this
file as having closed that gap.

---

## Membership — the family, not a fixed list of networks

Ethereum Classic mainnet and the Mordor testnet are today's members. **A
proof-of-work network added later falls under this protocol without amending
it.**

---

## Fork dispatch: this family activates at a block number

**Forks on these networks activate at a block number.** That is this family's
axis, and it is the fact this file exists to put in front of you before a
dispatch site is written.

**Do not carry the axis across families.** Another family's protocol states its
own, and `.claude/agents/forge.md` § "Fork dispatch" carries the shared
hazard: why the wrong axis is a consensus bug rather than a style problem, the
overloaded-dispatch-name trap inherited from the prior implementation, and the
rule that each family's fork-gated definitions stay independently defined. Read
it there — it is not restated here, and it binds regardless of which family you
are in.

---

## Standing properties of this family

- **Chain ID 61 on mainnet, 63 on Mordor.**
- **The EIP-1559 base fee is routed, not burned.** ECIP-1111 governs the
  destination — **read it there**, and never restate the address from
  recollection or from this file.
- **Block rewards follow the ECIP-1017 emission schedule** (below).
- **No blob transactions. No withdrawals. No post-merge header fields.**
- **This family keeps proof-of-work, fixed-supply emission, the traditional gas
  model, and pre-merge opcodes.**

**Reject any change that introduces another family's feature into this code
path**, however harmless the addition looks in isolation. What those features
are is stated by that family's own protocol, so read it rather than assuming the
contrast — the two families' properties are deliberately recorded once each,
under their own owner, rather than as a comparison table maintained in two
places.

---

## Emission

ECIP-1017 reduces the block reward by 20% every 5,000,000 blocks. Era 0
(0–5M) pays 5 ETC, era 1 pays 4 ETC, era 2 pays 3.2 ETC, and so on. This is a
fixed-supply schedule and it is state-affecting, so it is consensus — with
`banksy` as a required consult, because the tip floor it maintains is sized
against this declining schedule.

---

## The Olympia proposal set, and its boundaries

**This protocol states no Olympia specification content, and the omission is the
instruction.** `.claude/reference-corpus.md` § "Cite an Olympia ECIP; never
restate what it contains" is the authority; what follows is what a framework
document can hold without breaking it.

**The suite is under active rewrite, and its own membership moves.** A proposal
can be planned and referred to before it is authored, and one already authored
can be replaced rather than amended. **So there is no roster here, no
per-proposal summary, and no value** — read the current set, and each document's
content, from the specification at the moment you need it.

**A membership question has been got wrong from memory more than once**, in both
directions: an EIP attributed to the wrong ECIP, and an EIP treated as Olympia
that had shipped at an earlier fork. Every one of those readings was confident.
**Treat any recollection of which proposal carries what as unverified**,
including a recollection that arrives inside a task brief, and open the document.

**What consensus owns inside the set is decided by the litmus, not by proposal
number.** The state-affecting parts — fee-market mechanics, where a fee is
routed, the opcode and gas set — are consensus's. Operator-tunable client policy
in the same family is `banksy`'s: tip and price floors, the gas-target a producer
aims for, and MESS reactivation, that last one carrying a mandatory consensus
co-signature. **One proposal routinely splits across both owners**, which is why
the litmus rather than the number is the assignment rule. `banksy`'s charter
states the same split from its own side and carries the worked example.
