# Consensus — AuRa (authority round)

**provenance:** **two passes stand behind this file and they do not carry
the same weight. Read the split, not the word "verified."**

**The originating conformance pass** established these facts against the
reference clients with calibrated controls — each absence claim was run
alongside a probe that fired, so a nil result here is a reading rather than
a silence.

**A later audit, 2026-08-17, re-derived only part of it.** In this file it
corrected erigon's deletion paths, which it had under-counted at one and are
two. **Everything else here was not re-opened by that audit** and rests on
the originating pass alone.

**Four claims were re-derived and four were corrected. How those four were
selected is not recorded, so that rate does not estimate the error rate of
what was not re-opened** — an audit that targeted claims it already doubted
returns four-of-four against a near-clean remainder, and one drawing at
random does not. **So the un-audited majority of this file is neither
known-wrong nor known-right. It is un-re-derived, at an unknown rate.**
Named residuals carrying that standing here: nethermind's `ValidatorStore`
linked-list claims. **Re-derive one before relying on it; this header has
not done that for you.**

**The contrast with `.claude/protocols/consensus-pow.md` and
`.claude/protocols/consensus-pos.md` survives the downgrade, and it
matters.** Their domain facts were inherited from this project's prior
implementation and checked against nothing at all, which is why they carry a
`currency:` header. There are now three standings, not two — never checked,
checked once, checked and audited — and this file is the middle one
everywhere the audit did not reach. **Do not read their disclaimer onto this
file, and do not carry this file's standing onto them.** Checked 2026-08-17.

**What "verified" buys, and what it does not.** The citations name a client and a
file, **not a ref that cannot move** — `.claude/rules/evidence-and-citation.md`
§1's stronger form. So every claim here is **re-runnable rather than permanent**:
re-run it against the current corpus, and read a path that no longer resolves as
a stale citation rather than as a fact that went away.
`.claude/reference-corpus.md` is how a clone assembles the same clients.

**No frontmatter, and none is possible.** A file under `.claude/protocols/` does
not auto-load — Claude Code discovers `.claude/rules/`, not this directory — so
`paths:` here would do nothing. **Something has to reach this file by name**,
whether that is an import, a pointer a reader follows, or a task brief quoting
it. Nothing warns you when that stops happening.

**What reaches it is `.claude/agents/forge.md`**, which owns consensus for every
family and mechanism and instructs its reader to open this file before acting on
anything AuRa-shaped. That charter body loads on dispatch; this file does not.
**So an AuRa task that never opened this protocol is running on recollection**,
and the charter treats that as the same finding as the protocol being absent.

**This is a mechanism-fact protocol, not the deferred consensus-change
protocol.** The state-root litmus — the rule deciding whether a change is
consensus at all — is mechanism-neutral, still lives in the charters, and its
canonical home remains a consensus-change protocol this repository does not have
yet. Do not read this file as having closed that gap.

**It is also not a schedule.** `.claude/agents/forge.md` § "You own consensus,
not a family" forbids writing a protocol ahead of a network that concretely
exists, and `.claude/rules/reference-first.md` supplies the test it forbids by:
a shape may be widened only for a consumer checkable in the field **today**.
AuRa is running code in production clients this project's corpus already
carries, and what this file records is a **seam** — what a pluggable consensus
module and a keyspace must not foreclose. **It commits fukuii to implementing
nothing.** That rule's § "The recurring shape: narrow scope, wide-enough survey"
step 4 is exactly this act: record what would trigger building the deferred
consumer, and stop.

**The set of mechanism protocols is open.** These files exist because a survey
found these mechanisms to diverge from one another. **Nothing here implies the
set is closed, and a mechanism not yet surveyed does not belong in this file** —
it earns its own or it gets none. `ls .claude/protocols/` is the roster.

---

## AuRa differs from the rest of the surveyed set IN KIND, not in degree

**Read this section before any other, because every design instinct carried over
from Clique is wrong here.**

Clique persists as an optimization and one client declines to. **AuRa persists
mandatorily, in both implementations, and the record is not a cache.** Two
properties produce that, and both are structural:

- **The validator set is NOT derivable from headers.** It comes from
  `InitiateChange` events emitted by a validator contract — read out of
  **receipts** — combined with calls against that contract. nethermind's
  `Nethermind.Consensus.AuRa/Contracts/ValidatorContract.cs` states the shape in
  its own signature: `CheckInitiateChangeEvent(BlockHeader blockHeader,
  TxReceipt[] receipts, out Address[] addresses)`, consumed from
  `Validators/ContractBasedValidator.cs`. **A header-only node cannot
  reconstruct the set.** Control: the same sweep over nethermind's Clique tree
  finds no receipt dependency at all, so the instrument discriminates.
- **Records land on FINALITY, not on block height.** nethermind's
  `ValidatorStore.SetValidators` is keyed on a *finalizing* block number. So
  records arrive at irregular, chain-dependent heights, and **no interval
  arithmetic predicts where the next one is.** Any design assuming a fixed
  stride — the way Clique's 1024-block checkpoint admits — is wrong here.

---

## Two implementations, agreeing on the requirement, disagreeing on representation

| | nethermind | erigon |
|---|---|---|
| Where | the shared **block-info** database, taken as a generic `IDb` | dedicated tables `Epoch = "DevEpoch"`, `PendingEpoch = "DevPendingEpoch"` |
| Key | `Keccak.Compute("Validators" + blockNumber)` — a hash, so **unordered and not scannable** | `block_num_u64 ++ block_hash -> transition_proof` — **ordered and cursor-scannable** |
| Traversal | a **backward-linked list** | ordinary ordered lookup |
| Cited in | `Nethermind.Consensus.AuRa/Validators/ValidatorStore.cs` | `db/kv/tables.go`, `db/rawdb/accessors_chain.go` |

**nethermind's linked list is the detail most likely to be lost, and it decides
what a keyspace may do.** Each stored `ValidatorInfo` carries both its own
finalizing block number and its **predecessor's**, and `FindValidatorInfo` answers
a historical query by walking backward through them. **Deleting any interior
record breaks every query behind it** — not the one record, everything older.
Two pointer keys sit alongside the records:
`Keccak.Compute("LatestFinalizedValidatorsBlockNumber")` and
`Keccak.Compute("PendingValidators")`.

**Absence is fatal in both, and loudly so.** erigon panics —
`execution/protocol/rules/aura/aura.go`, `panic("genesis epoch transition must
already be set")`. nethermind throws — `ValidatorStore.LoadValidatorInfo`,
`InvalidOperationException`, `"No validator info for block number {n}."` **A
missing record does not degrade this mechanism to slow. It stops it.**

> **An instrument trap, recorded because it fires on the obvious search and
> because THREE names collide, not two.** AuRa's table is `kv.Epoch`. erigon also
> carries `kv.EpochData` — a **consensus-layer beacon-state** table, consumed
> under `cl/`, nothing to do with AuRa. And AuRa's own tree carries a
> `genesisEpochData` method family.
>
> **Two things make this worse than a different-tree mix-up.** `kv.EpochData` is
> **declared in `db/kv/tables.go`, the same file as `kv.Epoch` and about 130
> lines below it**, so opening the declaration site does not separate them
> either — both are right there, and only reading the consumer does. And a bare
> `EpochData` grep **scoped to AuRa's own tree** returns nothing but
> `genesisEpochData` tails: measured, every one of its ten hits, with a control
> confirming `kv.EpochData` appears in that tree not once.
>
> **So keep the `kv.` prefix on every search and every citation**, and treat an
> unprefixed `Epoch` or `EpochData` match as unread. `.claude/rules/evidence-and-citation.md`
> §3 — a grep is a search, not a finding; open the match, and keep opening until
> the match distinguishes itself. **That this passage is itself where the
> substring hazard bit is the argument for the rule, not an irony to note.**

---

## What the mechanism needs from a keyspace

**The namespace is this consensus module's, not the storage layer's** —
`.claude/agents/forge.md` § "A consensus mechanism owns its own namespace"
states the rule and the evidence, and AuRa is its strongest instance: nethermind
hands a large consensus module a **generic untyped `IDb`**
(`[KeyFilter(DbNames.BlockInfos)] IDb db`) and the module builds its keys, its
pointers and its linked list **inside** it. The storage layer models none of it.

So, stated as requirements *of* a keyspace rather than *on* one:

- **Durable, mandatory storage.** Not a cache, not optional, not recomputable.
- **Point lookup by a caller-derived key.** Ordering is *not* required — the two
  implementations split on it and both work — so a keyspace offering only point
  lookup is conforming.
- **Room for auxiliary pointer keys** beside the records, in the same space.
- **Every historical record retained**, because a historical query may traverse
  arbitrarily far back.

---

## Retention — the one property that is genuinely the storage layer's

**AuRa records are never reclaimed as history**, and **nethermind has no
deletion path at all.** erigon has **two**, and an earlier version of this file
reported only the first as its *only* deletion:

- **`DeleteNewerEpochs`**, whose own comment states its direction: *"drops
  [blockNum, ∞)"* — **forward truncation on a reorg unwind**, called from
  `execution/stagedsync/stage_execute.go`.
- **A wholesale table wipe.**
  `execution/stagedsync/rawdbreset/reset_stages.go` declares
  `var stateBuckets = []string{kv.Epoch, kv.PendingEpoch}` and `ResetExec` feeds
  it into the `cleanupList` handed to `backup.ClearTables` — **the entire
  keyspace, not a range.** It is reachable from the integration and step
  commands, so it is operator-invoked destruction followed by a rebuild, never
  anything a pruner does on its own.

> **The probe that would have caught this is the one this file already
> prescribes** in the instrument note above: keep the `kv.` prefix on every
> search. A repo-wide `kv.Epoch` search returns the `reset_stages.go` site
> **beside** the five accessors in `db/rawdb/accessors_chain.go` — one result
> set, both answers. **A file bitten by the discipline it teaches is the
> argument for the rule**, recorded here for the same reason the trap above is
> rather than quietly fixed.

**So the declaration the storage layer must honor is *"this keyspace is never
pruned"*, and it must honor it without knowing why.** For AuRa the cost of
getting it wrong is higher than for Clique: a reclaimed interior record does not
lose one answer, it **breaks the backward walk** and every older query with it.

**The wipe reconciles with that declaration rather than contradicting it, and
getting the reconciliation right is what a seam depends on.** *Never pruned* is
a statement about **automatic reclamation**, which is the pruner's business. A
reset is an **explicit, whole-keyspace destruction** with a different caller, a
different trigger and a rebuild behind it. **So a keyspace can be
never-pruned and still need a whole-keyspace-drop primitive** — and one of the
two implementations ships exactly that, so a storage layer offering only the
retention flag forecloses a path the field already uses. **Do not read the
never-pruned declaration as "no destructive operation exists."**

---

## Evidence weight

**Two independent implementations, in different languages, that disagree about
representation while agreeing about the requirement.** That is the strongest
form of evidence available for a mechanism — agreement on semantics with
disagreement on encoding means the semantics are settled and **the encoding is
ours to choose**, which is exactly the read
`.claude/rules/reference-first.md` § "What a survey has to produce" asks for.

**Two is nonetheless two.** It is a smaller corpus than Clique's three, and
neither implementation is evidence about the other.
