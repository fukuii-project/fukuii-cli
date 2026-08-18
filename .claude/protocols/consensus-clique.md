# Consensus — Clique

**provenance:** **two passes stand behind this file and they do not carry
the same weight. Read the split, not the word "verified."**

**The originating conformance pass** established these facts against the
reference clients with calibrated controls — each absence claim was run
alongside a probe that fired, so a nil result here is a reading rather than
a silence.

**A later audit, 2026-08-17, re-derived only part of it.** In this file it
corrected the count of implementations carrying a persisted snapshot, which
had over-counted at four and is three. **Everything else here was not
re-opened by that audit** and rests on the originating pass alone.

**Four claims were re-derived and four were corrected. How those four were
selected is not recorded, so that rate does not estimate the error rate of
what was not re-opened** — an audit that targeted claims it already doubted
returns four-of-four against a near-clean remainder, and one drawing at
random does not. **So the un-audited majority of this file is neither
known-wrong nor known-right. It is un-re-derived, at an unknown rate.**
Named residuals carrying that standing here: the besu `SegmentIdentifier`
claim. **Re-derive one before relying on it; this header has not done that
for you.**

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
anything Clique-shaped. That charter body loads on dispatch; this file does not.
**So a Clique task that never opened this protocol is running on recollection**,
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
Clique is running code in production clients this project's corpus already
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

## The mechanism, in the one paragraph the rest depends on

Clique is proof-of-authority. A set of authorized signers takes turns sealing,
and the set itself is changed by a running vote cast through block headers. **The
validator set is therefore derivable from headers alone** — that is the property
every storage answer below is a different response to, and it is what separates
Clique from AuRa.

---

## The divergence: one mechanism, opposite storage answers, both in production

**This is the finding this file exists for.** Clique does not have *a* storage
requirement. It has implementations that disagree about whether it needs storage
at all, and every one of them ships.

| Client | Where a snapshot lives | Key |
|---|---|---|
| go-ethereum, core-geth | the general chain-data store | `CliqueSnapshotPrefix = []byte("clique-")` ++ block hash — **prefix-scannable** |
| nethermind | the **blocks** database, shared with real blocks | the block hash **transformed**, see below — **not prefix-scannable** |
| besu | **nowhere. No keyspace at all** | — |

- **go-ethereum and core-geth** declare the prefix in `core/rawdb/schema.go` and
  write every `checkpointInterval = 1024` blocks (`consensus/clique/clique.go`).
- **nethermind** takes `[KeyFilter(DbNames.Blocks)] IDb blocksDb` in
  `Nethermind.Consensus.Clique/SnapshotManager.cs` and derives its key in
  `GetSnapshotKey`: a 32-byte value formed by XORing the **leading nine bytes**
  of the block hash with the ASCII bytes of `snapshot-` and **leaving the
  remainder zero**. The result is a `Hash256` — the same width as a real block
  hash, sharing a database with them, and carrying no prefix to scan. **You can
  fetch a snapshot only if you already hold the block hash.**
- **besu allocates no segment for any consensus mechanism.**
  `ethereum/core/.../storage/keyvalue/KeyValueSegmentIdentifier.java` enumerates
  its segments and **not one is consensus-specific** (control: `BLOCKCHAIN` and
  `WORLD_STATE` are present, so the instrument reads the file). Validators come
  from header `extraData` through `BlockValidatorProvider`, wired at the app
  layer in `app/.../controller/CliqueBesuControllerBuilder.java`; the vote tally
  behind it is a Guava in-memory cache, `maximumSize(100)`, in
  `consensus/common/.../validator/blockbased/VoteTallyCache.java`. Epoch length
  defaults to **30,000** in `config/.../JsonCliqueConfigOptions.java`.

**Read the besu column as the load-bearing one, and count the split correctly:
it is two to one, not three to one.** core-geth is not a second persistence
decision — see Evidence weight below. **The ratio was never the argument
anyway.** It is proof that **persistence here is an optimization, not a
requirement** — the set is derivable from headers, so a client may recompute
instead of storing, and one production client does. **A single counterexample
establishes that whatever the other column holds**, which is why correcting the
count changes nothing about the conclusion.

**Snapshots are keyed by block hash, not by height.** A reorg therefore
accumulates several snapshots at one height rather than replacing one. Any sizing
estimate that assumes one record per checkpoint height is wrong on a chain that
reorgs.

---

## What the mechanism needs from a keyspace

**The namespace is this consensus module's, not the storage layer's** —
`.claude/agents/forge.md` § "A consensus mechanism owns its own namespace"
states the rule and the evidence. So the requirement is stated as what Clique
needs *from* a keyspace:

- **A place to put opaque bytes under a caller-chosen key**, nothing more. Every
  client above builds its own key discipline inside a generic store; none asked
  the storage layer to model a snapshot.
- **No ordering and no range scan.** geth's prefix admits one and nethermind's
  key forecloses it, and both work — so ordering is not a requirement of the
  mechanism.
- **Nothing at all, optionally.** A conforming implementation may hold no
  keyspace and recompute from headers.

---

## Retention — the one property that is genuinely the storage layer's

**Clique snapshots are never pruned, in any client surveyed. No client has a
deletion path for them.**

geth does not merely leave them: it **measures** them as a growing database
category, reporting `{"Key-Value store", "Clique snapshots", …}` from
`core/rawdb/database.go`. Unbounded growth is a known and accepted property, not
an oversight.

**So the declaration the storage layer must honor is *"this keyspace is never
pruned"*, and it must honor it without knowing why.** That is the whole of the
storage layer's interest in Clique. A pruner that treats an unrecognized keyspace
as reclaimable is a correctness bug against this mechanism.

---

## Evidence weight

**Three independent implementations, in three language families.** An earlier
version of this file counted **four**, and that over-counted by treating
core-geth as independent of go-ethereum. It is not: its own `go.mod` declares
`module github.com/ethereum/go-ethereum`, and its Clique storage is
go-ethereum's verbatim — same `CliqueSnapshotPrefix`, same
`checkpointInterval = 1024`, same key layout, the diff confined to type
plumbing. **Counting it is counting one decision twice**, which is the test
`.claude/protocols/consensus-qbft.md` and `.claude/protocols/consensus-ibft2.md`
already apply to `besu-etc` and which this file had not applied to itself.

**The over-count announced itself in its own wording.** *"Four implementations
across three language families"* has two of its four sharing a language **and** a
lineage. **A count that does not divide evenly by family is the tell** — go read
the two that share one before believing the number.

**Still the best-evidenced mechanism of the surveyed set** — AuRa has two
implementations and the two BFT mechanisms have one apiece — **and still the
only one where a disagreement between implementations is itself the finding**
rather than a gap in the survey. That conclusion rests on the disagreement,
which three implementations carry exactly as well as four did.
