# Consensus — IBFT2

**provenance:** **two passes stand behind this file and they do not carry
the same weight. Read the split, not the word "verified."**

**The originating conformance pass** established these facts against the
reference clients with calibrated controls — each absence claim was run
alongside a probe that fired, so a nil result here is a reading rather than
a silence.

**A later audit, 2026-08-17, re-derived only part of it.** In this file it
corrected the claimed absence of a per-fork validator switch, which was
withdrawn — the switch exists. **Everything else here was not re-opened by
that audit** and rests on the originating pass alone.

**Four claims were re-derived and four were corrected. How those four were
selected is not recorded, so that rate does not estimate the error rate of
what was not re-opened** — an audit that targeted claims it already doubted
returns four-of-four against a near-clean remainder, and one drawing at
random does not. **So the un-audited majority of this file is neither
known-wrong nor known-right. It is un-re-derived, at an unknown rate.**
Named residuals carrying that standing here: the `RoundState` claims and the
besu `SegmentIdentifier` claim. **Re-derive one before relying on it; this
header has not done that for you.**

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
anything IBFT2-shaped. That charter body loads on dispatch; this file does not.
**So an IBFT2 task that never opened this protocol is running on recollection**,
and the charter treats that as the same finding as the protocol being absent.

**This file exists because IBFT2 and QBFT are NOT one answer.** They agree
exactly on storage and diverge on validator sourcing, and a merged file would
report the agreement and bury the divergence — see "Where it diverges from
QBFT" below, which is the whole reason for the split. Read
`.claude/protocols/consensus-qbft.md` alongside this one, always.

**This is a mechanism-fact protocol, not the deferred consensus-change
protocol.** The state-root litmus — the rule deciding whether a change is
consensus at all — is mechanism-neutral, still lives in the charters, and its
canonical home remains a consensus-change protocol this repository does not have
yet. Do not read this file as having closed that gap.

**It is also not a schedule.** `.claude/agents/forge.md` § "You own consensus,
not a family" forbids writing a protocol ahead of a network that concretely
exists, and `.claude/rules/reference-first.md` supplies the test it forbids by:
a shape may be widened only for a consumer checkable in the field **today**.
IBFT2 is running code in a production client this project's corpus already
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

## The storage answer: identical to QBFT, down to the data structure

**IBFT2 allocates no keyspace.** besu's
`ethereum/core/.../storage/keyvalue/KeyValueSegmentIdentifier.java` enumerates
its segments and **not one is consensus-specific** (control: `BLOCKCHAIN` and
`WORLD_STATE` are present, so the instrument reads the file). A sweep of the
IBFT2 tree's `src/main` for any storage handle returns nothing; **test
scaffolding constructs storage, production code does not.**

**Round state lives in memory and does not survive a process restart.**
`consensus/ibft/.../statemachine/RoundState.java` holds `prepareMessages` and
`commitMessages` in a `LinkedHashMap`; `.../statemachine/RoundChangeManager.java`
holds received round-change messages the same way — the same shapes, in the same
file names, as the QBFT tree. **No prepared certificate and no round-change
message is persisted.**

---

## Where it diverges from QBFT — the reason these are two files

**The axis this survey found is validator SOURCING.** IBFT2 is block-based
only; QBFT is block-or-contract. There is no `TransactionValidatorProvider`
anywhere in the IBFT2 tree. **Storage and validator sourcing are what was
compared, so read this as the divergence found rather than as the only one that
exists.**

Verified in both directions, which is what makes it a finding rather than a
silence:

- `TransactionValidatorProvider` resolves **only** under `consensus/qbft` —
  an exact-word search over `src/main`, the file being
  `consensus/qbft/.../validator/TransactionValidatorProvider.java`.
- The IBFT2 tree contains **no validator-provider file of any kind**. It calls
  the shared `BlockValidatorProvider`, and its consensus context is assembled
  one layer up, in `app/.../controller/IbftBesuControllerBuilder.java`.

**So IBFT2 never reaches world state to learn its validator set. QBFT
optionally does.** Validators come from the header's `extraData`, changed by
vote, with epoch length defaulting to **30,000** in
`config/.../JsonBftConfigOptions.java` — the same BFT config IBFT2 and QBFT
share, which is part of why the two read as interchangeable.

**IBFT2 DOES have a per-fork validator switch. An earlier version of this file
said it did not, and that was wrong.** The withdrawn claim read the `nonForking`
in `IbftJsonRpcMethods.java`'s call to
`BlockValidatorProvider.nonForkingValidatorProvider(...)` as characterizing the
mechanism. It does not, in two independent ways, either of which alone refutes
it:

- **That call site is a read-only JSON-RPC helper, and it feeds exactly one
  method** — `IbftGetSignerMetrics`. Every other IBFT2 JSON-RPC method in the
  same `create()` takes the provider from the `BftContext`. Its own comment says
  why it is separate: *"Must create our own voteTallyCache as using this would
  pollute the main voteTallyCache."*
- **QBFT calls the same factory identically**, in
  `QbftBesuControllerBuilder.createReadOnlyValidatorProvider`, carrying that
  same comment verbatim. **A call both mechanisms make distinguishes neither.**

**IBFT2's consensus path is wired with `forkingValidatorProvider`.**
`IbftBesuControllerBuilder.createConsensusContext` builds the `BftContext` that
every BFT header-validation rule reads its validators from, passing a
`BftValidatorOverrides` converted from
`genesisConfigOptions.getTransitions().getIbftForks()` — a real per-fork
validator override, configured by an operator as `transitions.ibft2[].validators`.
**A seam designed from the withdrawn claim would be unable to express that**,
which is why the correction matters past the sentence.

**Two different "forking" concepts share a stem, and conflating them is what
produced the error.** `BlockValidatorProvider.forkingValidatorProvider(...)`
overrides the validator *set* at fork blocks, and **both** mechanisms use it.
The `ForkingValidatorProvider` class under `consensus/qbft/.../validator/`
switches the validator *source* between block-based and contract-based, and is
QBFT's alone. Only the second is a divergence.

**Reporting the two as one answer would hide exactly this**, and it is the half
that decides whether the state store is reached at all. **A node running IBFT2
never executes a call to learn its validator set; a QBFT node in contract mode
does.** That is a deployment-shaped difference, not a naming one — and for what
that call actually requires, which is narrower than an archive node, read
`.claude/protocols/consensus-qbft.md` rather than inferring it from here.

> **Two instrument traps, and the second is the one that actually bit.**
>
> **The first fires on the obvious check and nearly reversed this file's central
> claim.** Sweeping the IBFT2 tree for `WorldState` **hits** —
> `IbftJsonRpcMethods.java` passes `context.getWorldStateArchive()` into
> `BlockchainQueries` for ordinary JSON-RPC service, not for validator sourcing.
> Opening the match settles it in a line.
>
> **The second is a substring, and it produced the withdrawn claim above.**
> `ForkingValidatorProvider` is a substring of `nonForkingValidatorProvider`, so
> a sweep of the IBFT2 tree for the class name returns **exactly one hit, and it
> is not the class** — it is the `nonForking` factory call inside
> `IbftJsonRpcMethods.createValidatorProvider`. A sweep that cannot tell a class
> from the negation of its own name is not measuring what its pattern says.
>
> **And a scope trap underneath both: the consensus wiring is not in the
> consensus tree.** `consensus/ibft/src/main` does not contain the call that
> decides IBFT2's validator provider; `app/.../controller/` does. **A sweep
> bounded by the mechanism's own directory cannot see the mechanism's own
> wiring** — which is how a JSON-RPC helper came to be read as the consensus
> path. Search `app/` too, or the absence you find is the boundary's.
>
> `.claude/rules/evidence-and-citation.md` §3 — a grep is a search, not a
> finding; open the match, and check what the pattern would match that you did
> not want.

---

## What the mechanism needs from a keyspace

**Nothing, and unlike QBFT there is no constraint hiding elsewhere either.**

**The namespace is this consensus module's, not the storage layer's** —
`.claude/agents/forge.md` § "A consensus mechanism owns its own namespace"
states the rule. IBFT2 is its cleanest degenerate case: the module asks for no
keyspace, and it asks nothing of the state store either, so a node running it is
unconstrained in both directions by its consensus mechanism.

---

## Retention

**Not applicable — there is nothing to retain.** The retention declaration that
Clique and AuRa require of the storage layer has no IBFT2 equivalent, because
there is no keyspace to declare anything about.

---

## Evidence weight — read this before treating anything above as a requirement

**besu alone.** This is the weakest evidence base of the surveyed set, tied with
QBFT, and weak in the same specific direction.

`besu-etc` is a **frozen fork of the same code**. It corroborates the segment
absence and nothing else — **it is not an independent design**, and counting it
would be counting one decision twice.
`.claude/reference-corpus.md` says the same of it from its own side.

**So a shape derived from this file is fukuii inheriting one client's choice as
though it were the mechanism's requirement.** IBFT2 is a specified protocol and
besu is one implementation of it; everything above is besu's answer, not
necessarily IBFT2's. Where a design decision would be expensive to reverse,
treat this file as one data point and go to the specification —
`.claude/agents/forge.md` § Provenance already forbids citing a document in this
repository as the authority for a value, and that forbidding is at its strongest
here.

**One thing this file's narrowness does NOT weaken.** The QBFT/IBFT2 divergence
is a comparison **within** one client's own tree, so it does not depend on a
second implementation to be sound. besu being the only source limits what can be
concluded about each mechanism in general; it does not limit the conclusion that
besu treats them differently.
