# Consensus — QBFT

**provenance:** **two passes stand behind this file and they do not carry
the same weight. Read the split, not the word "verified."**

**The originating conformance pass** established these facts against the
reference clients with calibrated controls — each absence claim was run
alongside a probe that fired, so a nil result here is a reading rather than
a silence.

**A later audit, 2026-08-17, re-derived only part of it.** In this file it
corrected the contract-mode state requirement, which had read as an archive
requirement on every node and is confined to the JSON-RPC callers.
**Everything else here was not re-opened by that audit** and rests on the
originating pass alone.

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
anything QBFT-shaped. That charter body loads on dispatch; this file does not.
**So a QBFT task that never opened this protocol is running on recollection**,
and the charter treats that as the same finding as the protocol being absent.

**Read `.claude/protocols/consensus-ibft2.md` alongside this one, always.** The
two mechanisms give the **same** storage answer and **different** validator
answers, which is precisely the shape a single merged file would hide. A task
touching either reads both.

**This is a mechanism-fact protocol, not the deferred consensus-change
protocol.** The state-root litmus — the rule deciding whether a change is
consensus at all — is mechanism-neutral, still lives in the charters, and its
canonical home remains a consensus-change protocol this repository does not have
yet. Do not read this file as having closed that gap.

**It is also not a schedule.** `.claude/agents/forge.md` § "You own consensus,
not a family" forbids writing a protocol ahead of a network that concretely
exists, and `.claude/rules/reference-first.md` supplies the test it forbids by:
a shape may be widened only for a consumer checkable in the field **today**.
QBFT is running code in a production client this project's corpus already
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

## The storage answer: nothing, and it is discarded on restart

**QBFT allocates no keyspace.** besu's
`ethereum/core/.../storage/keyvalue/KeyValueSegmentIdentifier.java` enumerates
its segments and **not one is consensus-specific** (control: `BLOCKCHAIN` and
`WORLD_STATE` are present, so the instrument reads the file). A sweep of the
QBFT tree's `src/main` for any storage handle returns nothing; **test
scaffolding constructs storage, production code does not.**

**Round state lives in memory and does not survive a process restart.**
`consensus/qbft-core/.../statemachine/RoundState.java` holds `prepareMessages`
and `commitMessages` in a `LinkedHashMap`;
`.../statemachine/RoundChangeManager.java` holds received round-change messages
the same way. **No prepared certificate and no round-change message is
persisted.** A restarting node rebuilds its round state from the network rather
than from disk.

**Do not record this as "QBFT needs no storage" and stop there.** That sentence
is true of the *keyspace* and false of the *node*, because of the next section.

---

## Two validator sources, switched per fork — and one of them reaches world state

**This is the finding most likely to be lost, so it is stated before the
detail.** QBFT's contract mode makes a consensus mechanism reach into **the
state half of the node**: to learn its validator set it executes a call against
world state, at a block the caller supplies. Nothing about the consensus
module's own storage footprint — which is zero — hints at that, which is exactly
why it gets lost.

**Which block the caller supplies is the whole of the requirement, and an
earlier version of this file got it wrong.** It said a QBFT node in contract
mode *cannot run against a pruned or latest-only state store* — an archive
requirement on every contract-mode node. **The call sites do not support that**,
and they were enumerated rather than sampled:

- **Every consensus-path caller passes the PARENT header.**
  `consensus/qbft-core/.../validation/MessageValidatorFactory.java` calls
  `getValidatorsAfterBlock(parentHeader)` at both of its lookup sites, and
  `.../statemachine/QbftBlockHeightManager.java` does the same for both
  `getValidatorsForBlock` and `getValidatorsAfterBlock`. **A node holds the
  parent state at import time**, pruned or not, so contract mode is satisfied by
  an ordinary full node.
- **The arbitrary-block callers are JSON-RPC**, not consensus:
  `consensus/qbft/.../jsonrpc/methods/QbftGetValidatorsByBlockHash.java` and
  `.../QbftGetValidatorsByBlockNumber.java` take the block from the request.

**So the supported claim is narrower, and it splits in two.** Contract-mode QBFT
reads world state **at the parent of the block under validation** — a
requirement that the state store be *present and executable*, not that it be
deep. An **archive** requirement exists only for **historical validator RPC**,
which is an operator's choice about which endpoints to serve rather than a
property of running the mechanism.

**Do not restore the withdrawn form, and do not read the narrowing as an
all-clear either.** Contract mode still couples consensus to the state half —
`ValidatorContractController` **throws** rather than degrading when the call
cannot be served — which is the part that must survive into any seam. What
changed is the depth, not the coupling.

`consensus/qbft/.../validator/ForkingValidatorProvider.java` selects between the
two per fork:

- **Block-based.** Validators are carried in the header's `extraData` and
  changed by vote, served by `BlockValidatorProvider` with the Guava vote-tally
  cache in `consensus/common/.../validator/blockbased/VoteTallyCache.java`
  (`maximumSize(100)`). Epoch length defaults to **30,000** in
  `config/.../JsonBftConfigOptions.java`.
- **Contract-based.** `consensus/qbft/.../validator/TransactionValidatorProvider.java`
  delegates to `.../validator/ValidatorContractController.java`, which calls the
  validator contract through a `TransactionSimulator` **at a caller-supplied
  block number**. It carries its own Guava caches (`maximumSize(100)` each), and
  it **throws** rather than degrading when the call cannot be served.

---

## What the mechanism needs from a keyspace

**Nothing.** That is the honest answer and it should be recorded as one, not
softened into a small requirement.

**The namespace is this consensus module's, not the storage layer's** —
`.claude/agents/forge.md` § "A consensus mechanism owns its own namespace"
states the rule. QBFT's instance of it is the degenerate one: the module asks
for no keyspace, so the storage layer models nothing and declares nothing.

**The requirement QBFT does impose lands somewhere else entirely**: on the state
store, via contract mode, per the section above. **Do not let a zero in the
keyspace column read as a zero overall.**

---

## Retention

**Not applicable — there is nothing to retain.** The retention declaration that
Clique and AuRa require of the storage layer has no QBFT equivalent, because
there is no keyspace to declare anything about.

**The state-store constraint is not a retention declaration and must not be
recorded as one.** It is a configuration requirement on a different subsystem,
and it is enforced by that subsystem failing, not by a pruner honoring a flag.

---

## Evidence weight — read this before treating anything above as a requirement

**besu alone.** This is the weakest evidence base of the surveyed set, and it is
weak in a specific direction worth naming.

`besu-etc` is a **frozen fork of the same code**. It corroborates the segment
absence and nothing else — **it is not an independent design**, and counting it
would be counting one decision twice.
`.claude/reference-corpus.md` says the same of it from its own side.

**So a shape derived from this file is fukuii inheriting one client's choice as
though it were the mechanism's requirement.** QBFT is a specified protocol and
besu is one implementation of it; everything above is besu's answer, not
necessarily QBFT's. Where a design decision would be expensive to reverse, treat
this file as one data point and go to the specification —
`.claude/agents/forge.md` § Provenance already forbids citing a document in this
repository as the authority for a value, and that forbidding is at its strongest
here.
