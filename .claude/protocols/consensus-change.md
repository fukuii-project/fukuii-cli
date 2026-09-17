# Consensus change — the litmus, the tiebreak, precompile registration, and the system-call seam

**provenance:** the state-root litmus is this project's own routing policy —
decided by the operator, stated in `.claude/agents/forge.md` and
`.claude/agents/banksy.md`, and not an external fact that needs verifying
against a specification. **The unobservable-divergence tiebreak and the
system-call seam facts were originally drawn from this build's own
implementation** — `org.fukuii.execution.SystemCall` and
`org.fukuii.evm.JournaledWorldState` — which itself cites the executable
specification and the production clients at refs that cannot move. Every ref
was independently resolved against this project's reference corpus,
2026-09-11 — not merely copied.

**Corrected 2026-09-17, and the correction is why this header now says
different things about different parts of the file.** The system-call seam's
facts 2 and 3 were stated of system calls in general and were properties of
the one system call this build had built. **Prague falsifies fact 2
outright**; **fact 3's literal claim survives and was merely unscoped** — see
each fact for which.

**The corrections were reviewed, and the review found the correction
committing the same error against the same source.** The three-axis
subsection, the empty-target column and the applicability table below exist
because of that second pass. **Treat the reviewed state as the current one and
this note as why the file argues its own thesis against itself.**

**Evidence standing now varies by section, which the "Evidence weight" section
at the foot states per part.** In short: the seam corrections, the axes, the
clients-diverge rule and the which-text-governs rule were **verified directly
against primary sources** at that date — `ethereum/execution-specs` at
`0cc100eb1`, the published fixture release at `tests-v20.0.1`,
`ethereum/EIPs` at the named commits, `ethereum/go-ethereum`
`params/config.go`, and `gnosischain/specs` at `045d46d6d`. **The
precompile-registration section is the exception and is mostly a
consolidation** of this build's own code, with only the Osaka address read
from a specification.

**The durable lesson, which outlives the specific correction:** a fact stated
of a category, derived from the single member of that category which existed
at the time, reads exactly like a fact about the category. **Nothing in the
wording marks it as a sample of one.** Where this file states something as
general, it now says which members were read.

**No frontmatter, and none is possible.** A file under `.claude/protocols/`
does not auto-load — Claude Code discovers `.claude/rules/`, not this
directory — so `paths:` here would do nothing. Something has to reach this
file by name, whether that is an import, a pointer a reader follows, or a task
brief quoting it. Nothing warns you when that stops happening.

**What reaches it is `.claude/agents/forge.md`**, which owns consensus for
every family and mechanism and instructs its reader to open this file before
acting on any consensus change, in any family. That charter body loads on
dispatch; this file does not. **So a consensus task that never opened this
protocol is running on recollection**, and the charter treats that as the same
finding as the protocol being absent.

**This is the deferred consensus-change protocol.** Every family and mechanism
protocol in this directory — `consensus-pow.md`, `consensus-pos.md`,
`consensus-clique.md`, `consensus-aura.md`, `consensus-qbft.md`,
`consensus-ibft2.md` and `consensus-ethash.md` — says, in near-identical
words, that its own file is "not the deferred consensus-change protocol" and
that the protocol's canonical home is one this repository "does not have yet."
Both charters say the same. **This is that file.** Where one of those
documents still reads that way, its own sentence is stale, not this one, and
the fix in every case is the same: correct the sentence in place rather than
appending a note about the correction — the clause claiming the protocol does
not exist becomes a citation to this file.

---

## The state-root litmus

**Does the change alter the state root?**

- **YES → consensus, and consensus is `forge`'s** — whichever family it
  belongs to. Balances, storage, emission, treasury credits, withdrawals
  credited, anything hashed into a state or receipts root. A single divergent
  implementation forks the chain.
- **NO, and the policy is operator-tunable without a hard fork → `banksy`'s.**
  Mempool admission, block-production transaction selection, tip and price
  floors, gas-target enforcement, peer scoring and retention, subjective
  fork-choice scoring.
- **NO, and it is NOT operator-tunable → still `forge`'s.** A protocol
  obligation on a boundary this client does not define alone — the
  consensus-layer seam (`consensus-engine-api.md`) is the standing case.
  Answering a driver verb in the wrong order, or with a status the
  specification does not allow, breaks the pair without moving a state root,
  and no operator setting makes it correct.

**Both branches of the NO case are needed.** A two-branch form reads as a
default to `banksy`, and there is no third owner to fall through to. The
conjunction is what does the work: a concern that moves no state root and is
**not operator-tunable without a hard fork** was never `banksy`'s.

**The worked example, because the rule is useless without one.** Stated
alone, the litmus reads as obvious and then gets applied by intuition — and
intuition puts two identically shaped floors from one proposal family on the
same side, which is wrong. `banksy.md` § "The load-bearing litmus" carries the
case: one proposal family sets a minimum miner tip and a minimum base fee, and
the two floors land on opposite sides of this litmus because the base fee is
routed to a balance the specification names — a balance change — and the
miner tip is not. **Read the values from the proposals, never from a charter
or from this file**: the Olympia suite is under rewrite and no document in
this repository's framework layer carries its content —
`.claude/reference-corpus.md` § "Cite an Olympia ECIP; never restate what it
contains."

**Where the litmus is genuinely unclear, say so and ask for a joint read
between `forge` and `banksy`. Do not guess.** The costs are asymmetric: a
consensus change wrongly scoped to client policy is a chain-split risk; a
client-policy change wrongly escalated is one wasted review.

**Keeping every restatement of this litmus in agreement is a standing
obligation, not a one-time check.** `banksy.md` carries it twice — compactly
in its `description`, in full in its body — and until 2026-09-09 the two
disagreed: the `description` stated the conjunction above, the body stated an
unconditional `NO → yours` and demoted tunability to a "hallmark" rather than
a necessary condition, and a dispatched `banksy` reads the body.

**Do not enumerate the carriers as a closed roster — a list naming only
`banksy.md` and `forge.md` was already wrong the day it was written.**
`herald.md`'s own peer-scoring boundary states the identical conjunction
(*"alters no state root and is operator-tunable without a hard fork"*), and
any charter routing a decision through this litmus can state its own copy
without this file being told. Sweep for it instead of trusting a list:

```
git grep -n 'state root' .claude/agents/*.md .claude/protocols/*.md
```

then read each hit for whether it restates the conjunction, and check it
against this file's own wording. **A line-based sweep misses a restatement
that wraps across a line break** — `herald.md`'s own copy splits
`operator-tunable` from `without a hard fork` at exactly such a break — so
where a charter is known to touch this boundary and a sweep still returns
nothing for it, read the surrounding paragraph before concluding it is
silent.

**If a copy is ever found to disagree with this one, restore the conjunction
on the drifted copy — never widen `banksy`'s NO branch to absorb what fails
it.** That repair looks obvious from the asymmetry alone and is the wrong
one: it would make `banksy` the owner of every protocol obligation that moves
no state root, including the consensus-layer seam `consensus-engine-api.md`
governs.

---

## The unobservable-divergence tiebreak

**Where a divergence between the executable specification and the production
clients is unobservable, the tiebreak is whether the normative source's own
stated reasoning ESTABLISHES the choice immaterial.**

**That is an inference from the source's reasoning, not a search for a
sentence declaring "either reading is fine," and the inference typically sits
one step past the quotation that carries it.** A source stating that its own
choice is harmless has not yet stated that the opposite choice is too; the
second claim follows from the *reason* given for the first, when that reason
does not depend on which way the choice went — Instance 1 below works this
out in full, because the specification's own text there only ever asserts the
first half. **Put at its shortest: the specification's authority on an
unobservable divergence is conditional on it having reasoned about the
point, not on it having merely stated a value.**

- **Where the source's reasoning establishes it, there is no tie to break.**
  Both readings conform — the clients are exercising latitude the
  specification granted, not contradicting it — and this build's own siding
  with the specification in that case is a **free preference the
  immateriality permits, not a consequence the evidence forces.**
- **Where nothing in the source's reasoning establishes it, the disagreement
  is real.** The value is implemented from the widest independent evidence
  available, marked **UNSETTLED**, and carries the trigger that would reverse
  it.

**"Unobservable" is the whole boundary, and it does not soften anywhere
inside it.** An observable divergence between this build and a production
client is a chain split — a defect to fix, never a tie to break. What follows
governs only the case where the sources disagree and no block on any network
in scope can tell.

### When the production clients diverge observably FROM EACH OTHER

**The paragraph above assumes the clients agree and this build might not. They
do not always agree.** Where a rule is observable and the production clients
themselves split on it, "match the clients" names no action, and neither the
litmus nor the tiebreak above reaches the case.

**The rule this build applies: follow the weight of independent lineages,
implement the rule, and record in the source both who does not and what would
reverse it.** State the divergence as being from a named subset, never from
"the field" — a build that says it differs from *the clients* when it differs
from one of five has misdescribed its own position.

**"Lineage" and "implementation" are different units and this file uses both.
Where a headcount carries an argument, the unit is LINEAGE.** Measured by root
commit: `ethereum/go-ethereum` and `erigontech/erigon` share
`5db3335dce766bd679c54ea44f6df08a7ff74762`, so they are **one** lineage;
`NethermindEth/nethermind` (`3cd1daaa…`) and `besu-eth/besu` (`7dfc2e40…`) are
two more. **A reading that counts those four as four independent witnesses
over-counts by one**, and the older instances below do exactly that — they say
"four implementations", which is true as a statement about code read and
misleading as a statement about independent agreement. **Their verdicts are
unchanged** (three lineages against one specification still favors the
clients); what is wrong is the arithmetic the argument leans on, so read those
instances' counts as implementations and re-derive the lineage count before
resting anything new on them.

**Worked instance, section 31: the header gas-limit maximum of `2^63 - 1`.**
The go-ethereum lineage, `besu-eth/besu-etc`, `NethermindEth/nethermind` and
`ethereum/retesteth` enforce it; `lambdaclass/ethrex` @ `3954106507` does not
(`crates/common/types/block.rs:509-515`); and **the executable specification
is itself divided** — its test vocabulary names the rule while its loader
skips the case. `besu-eth/besu` is a fourth position again: it states the
figure and **cannot reach it**, because a header's limit is a signed `long`
and the reader assembles `2^63` into `Long.MIN_VALUE`, so its comparison never
fires and its merge rules refuse such a block at the gas-used rule instead.
This build checks it, and `HeaderValidator.scala:866-900` records the whole
split plus the reversal trigger — the specification adopting the maximum,
rejecting it, or a production client family dropping it.

**Note what makes this decidable at all: `ethereum/legacytests` @ `1f581b8cc`
states the block** — a limit of `2^63` over a parent at `2^63 - 1`. **A
divergence the clients disagree about is often one some corpus already
decides**, so look for the case before reaching for this rule.

**A tempting third answer was considered and REJECTED, and the rejection is
recorded because it will be proposed again:** *"the published fixtures are
generated from the executable specification, so matching the specification is
what certification rewards."* It cannot bite. An unobservable divergence is by
construction one no fixture can discriminate — a corpus compares roots and
hashes, and a value that moves neither is a value it cannot see. That is
**measured** at both worked instances below, not reasoned from the
definition: in each case the one contract this build has seen deployed, and
every published case's contract, are read directly and found unable to
exercise the disagreement at all.

### Which text of a proposal governs — "Final" and "activated" are independent

**A proposal's text keeps moving after the network implements it, and its
`status` field does not bound that.** So *"the specification says X"* is
incomplete until it says *which* text, at which ref — and the tiebreak above
cannot be applied to a quotation whose date nobody established.

**Worked instance, EIP-7702's refund rule, every date resolved against the
corpus rather than recalled:**

| | |
|---|---|
| Sepolia activates Prague | 2025-03-05 |
| Holesky activates Prague | 2025-03-26 |
| **A normative step edited AND a normative sub-bullet deleted** (`ethereum/EIPs` `00cc881b`, *"Remove trie concept from the vm specification"*) | **2025-04-30** |
| **Mainnet activates Prague** (`ethereum/go-ethereum` `params/config.go:62`, `PragueTime` 1746612311) | **2025-05-07** |
| **EIP moved to Final** (`e17d216b`) | **2025-06-03** |

**What `00cc881b` actually did, since calling it a rewording understates it:**

```diff
 6. Verify the nonce of `authority` is equal to `nonce`.
-   * If `authority` does not exist in the trie, verify `nonce` is equal to `0`.
 7. Add `PER_EMPTY_ACCOUNT_COST - PER_AUTH_BASE_COST` gas to the global refund
-   counter if `authority` exists in the trie.
+   counter if `authority` is not empty.
```

**A normative sub-bullet was deleted from step 6**, and *"exists in the
trie"* → *"is not empty"* is a semantic clarification rather than a
synonym — the two coincide only because EIP-161 makes empty accounts
non-persistent.

**Read the order: the text changed substantively after two public testnets
were already running it, and the proposal went Final a month AFTER mainnet
activation.** Neither "Final" nor "activated" is a freeze.

> **One row was removed from this table on review.** It cited a 2025-10-08
> commit as "edited again after Final". That commit is `[EIP-2929](eip-2929.md)`
> → `[EIP-2929](./eip-2929.md)` — link formatting, carrying no normative
> weight. **It read as evidence that substance keeps moving after Final, and
> it is not that.** The two rows above carry the argument on their own; padding
> it with a cosmetic commit weakens it.

**The rule.** Cite a proposal at an immutable ref, and where a claim rests on
wording that changed, say which wording and when. **Where the two readings are
observably different, the network's behavior governs and the proposal's
current text does not** — the clients implemented some text, and which one is
a question about the chain rather than about the document.

> **This corrects the finding that asked for this section, not only the
> protocol.** The finding described the instance as *"a Final proposal's prose
> edited after activation"*. Measured, `00cc881b` lands **before** mainnet
> activation and **before** Final — it is after *testnet* activation. The
> general gap is real and is in fact wider than the finding stated, since the
> Final transition itself postdates mainnet; the specific characterization was
> an inference one step past the verified commit, which is the failure mode
> `.claude/rules/evidence-and-citation.md` §5 names.

### Instance 1 — the account-creation marker, EIP-6780's `SELFDESTRUCT` scope

**The specification keeps a created-in-transaction marker across a revert. Of
the four production clients read, one keeps it too and three roll it back.
This build follows the specification, alongside the one client that already
does.**

`ethereum/execution-specs @ 0cc100eb1`, `forks/cancun/state_tracker.py:615,
425-427`: *"The parent reference and `created_accounts` are shared (not
rolled back)"*, and, of the marker specifically, *"The marker is not removed
even if the account creation reverts. Since the account cannot have had code
prior to its creation and can't call `get_storage_original()`, this is
harmless."* Quoted through to its own verdict rather than cut a sentence
earlier — stopped early, this reads as the specification stating a rule three
clients break, which is a different and stronger claim than the text
supports.

**What the specification states is that ITS choice is harmless; that the
opposite choice is equally harmless is one step past the quotation.** It
follows from the reason given, not from a second statement: the premise is
that a marked account can never be asked about, and an account that can never
be asked about carries a marker whose value is unobservable however a client
records it. So the three clients below that roll the marker back are
exercising the specification's own latitude, not contradicting a rule it
states — which is what makes this the "declares immaterial" branch rather
than the "nothing declares anything" one.

**Three of the four clients read roll the marker back on revert.**
`ethereum/go-ethereum @ 02872e9ef` holds it as `newContract` on the state
object and journals the write, so `createContractChange.revert` sets it false
(`core/state/journal.go:480-482`, written by `StateDB.CreateContract` at
`core/state/statedb.go:677-682`). `besu-eth/besu @ b330564a94` holds an
`UndoSet<Address> creates` on `TxValues` and undoes it in `undoChanges(mark)`
(`evm/.../frame/TxValues.java:55,159`), reached from
`MessageFrame.rollback()` (`:1505`) and from `AbstractMessageProcessor`
(`:138`), which both its revert and its exceptional-halt paths call.
`erigontech/erigon @ ab8e9fde7` marks a freshly created object
`newlyCreated` — *"true if this object was created in the current
transaction"* (`execution/state/state_object.go:103`), consulted at
`execution/state/intra_block_state.go:1900-1908` ("Used for EIP-6780") — and
a revert of the journal's `kindCreateObject` entry deletes the object
outright (`execution/state/journal.go:170-171,287-292`) rather than clearing
the flag in place, so the marker is gone along with the account it was on.

**`NethermindEth/nethermind @ 3a98e0818` is the fourth client, and it keeps
the marker — agreeing with the specification rather than with the other
three.** It tracks a creation on `CreateList`, a plain `HashSet<AddressAsKey>`
(`src/Nethermind/Nethermind.Evm/StackAccessTracker.cs:21,58,110`), consulted
for the identical SELFDESTRUCT-scope check
(`src/Nethermind/Nethermind.Evm/Instructions/EvmInstructions.ControlFlow.cs:273`).
Unlike its sibling `JournalSet` fields on the same tracker — accessed
addresses, accessed storage cells, and the destroy list, all restored on a
sub-call revert — `CreateList` carries no snapshot and is **absent from
`Restore()`** (`StackAccessTracker.cs:118` clears it only once, at the end of
the top-level execution). So it survives a sub-call revert unchanged, exactly
as the specification directs.

**So the split is two sources keeping the marker — the specification and
nethermind — against three clients rolling it back, not the specification
standing alone against every client.**

**The unobservability is derived, not merely asserted, and the derivation is
checkable:** a creation only reaches an address holding no code; a creation
that then fails is undone, so the address holds no code afterward either.
Both readers of the marker — EIP-6780's narrowed `SELFDESTRUCT` scope, and the
committed value a store is priced against — are reached only by running code,
and an address with none runs nothing. A later creation at the same address
records the marker again. So a marker this build and nethermind keep, and the
other three clients drop, can only ever be asked about an address that cannot
ask.

**Corroboration, not proof:** `state_tests/for_cancun/cancun/eip6780_selfdestruct`
carries 136 cases built for exactly this interaction, and
`cancun/eip1153_tstore` carries 123 more, all 259 agreeing under this build's
reading — recorded in `CertificationCorporaSpec`'s coverage matrix
(`GeneratedCancunSelfDestructCorpus`, 136 cases, and
`GeneratedCancunTransientStorageCorpus`, 123), which absorbed this differential
when the standalone composition spec was folded into the schedule. A corpus
filled from the specification cannot state an expectation for a behavior the
specification calls harmless, so what those 259 cases establish is that the
shape is exercised and nothing diverged — never that no discriminating input
exists.

### Instance 2 — a system call's `GASPRICE`, EIP-4788's beacon-root call

**The specification fills a system call's gas price from the block's base
fee; every client read reports zero instead. Nothing declares the choice
immaterial. This is UNSETTLED, and this build follows the clients.**

`ethereum/execution-specs @ 0cc100eb1` `forks/cancun/fork.py:578` builds its
transaction environment with `gas_price=block_env.base_fee_per_gas`. Every
client read states zero: `ethereum/go-ethereum @ 02872e9ef`
`core/state_processor.go:326` (`GasPrice: uint256.NewInt(0)`),
`besu-eth/besu @ b330564a94` `SystemCallProcessor.java`'s
`.gasPrice(Wei.ZERO)`, `NethermindEth/nethermind @ 3a98e0818`
`BeaconBlockRootHandler.cs`'s `GasPrice = 0`, and `erigontech/erigon @
ab8e9fde7` `execution/protocol/block_exec.go:228-240`, whose sixth positional
argument to `NewMessage` is the gas price and is `&u256.Num0`.

**Nothing declares this immaterial**, which is what puts it on the other side
of the tiebreak from Instance 1. `ethereum/EIPs @ d2a64c2d4`, `EIPS/eip-4788.md`
(Final) says nothing about it; the specification fills a general-purpose
constructor's field without comment; and four implementations across three
language families fill it otherwise. The value cutting the other way is
recorded rather than omitted: five further fields of that same constructor
*are* given values that say "this is not a transaction" — empty access lists,
no blob hashes, no index in the block, no transaction hash — so the
specification was willing to think about that constructor's fields, and
filling this one from the block may be a choice rather than an oversight. It
changes nothing here: the tiebreak turns on whether the divergence was
declared immaterial, and no source declares it either way.

**This build follows the clients** — four implementations across three
language families against one specification — and the proposal's own
argument reinforces the choice rather than merely outnumbering the
specification: *"the call does not follow the [EIP-1559] burn semantics"*
(`EIPS/eip-4788.md`), which argues against reporting the very charge that
mechanism sets. Implemented as `BigInt(0)`, marked UNSETTLED.

**Reversing trigger:** a network deploying a system contract that reads
`GASPRICE`. On such a network the two readings are different state, and the
question has to be settled from that network's own specification rather than
from this constant.

**Measured, not assumed, that no published case can decide it.** Walking the
deployed beacon-roots contract's runtime code and skipping every push
immediate leaves `CALLER` and `TIMESTAMP` as the only environment operations
it reaches — `GASPRICE` is absent as an instruction and its byte is absent
from the code entirely. And no contract that *would* read one is reachable
through the published corpus either:
`org.fukuii.chainspec.certification.BeaconRootCorpus.BeaconRoots`'s own
docstring states, of the address every published file uses, *"across every
published case, the code at this address is byte-identical canonical code or
nothing at all, which is why nothing in this corpus can decide
`SystemCall.GasPrice`."* So no published case could have run a contract that
asked, which is the measurement behind the rejected tiebreak above at this
instance specifically.

### The rule generalizes past both instances

Neither worked instance is the boundary of the rule. Any future site where
this build's own reading of the executable specification disagrees with the
production clients, with no block on any network in scope able to tell the
difference, is governed by the same tiebreak: check whether the normative
source's own stated reasoning establishes the choice immaterial before
treating the disagreement as real, and never let "the published fixtures
reward one side" decide it — an unobservable divergence is, by the same
construction every time, one no fixture can discriminate.

**The rule is not a rationalization for whichever side this build already
picked, and that is demonstrated rather than asserted.** Tested against every
simpler rule that would also fit these two outcomes — always follow the
specification, always follow the clients, follow whichever side has more
implementations — none of them reproduces both instances at once. Instance 1
sides with the specification against a majority of clients (three of four
roll the marker back; the specification and one client, nethermind, keep
it); Instance 2 sides with the clients against the specification, by a
unanimous four-client reading. A rule that always followed the specification
gets Instance 2 wrong; a rule that always followed the clients, or that
followed whichever side had more implementations, gets Instance 1 wrong the
moment a fourth client is read. **Widening Instance 1's survey from two
clients to four makes the case stronger, not weaker**: this build now sides
with a minority reading at Instance 1 and a majority reading at Instance 2,
which no rule keyed to a headcount could produce either way. What decides
both is whether the *source's own reasoning* establishes the choice as
immaterial — present at Instance 1, absent at Instance 2 — independent of how
many clients land on which side.

---

## Registering a precompile changes gas for transactions that never call it

**Two effects, and they have different reaches. Do not carry the second one
to a fork that predates warm/cold accounting.**

**Effect A — the whole-fork gas effect, and it exists only from Berlin.**
Where a fork meters state access as warm/cold, a precompile's address is warm
from the start of every transaction, so the set of registered addresses is an
input to gas accounting for the whole fork — not only for the transactions
that call one. In this build the seed is taken inside the `WarmCold` arm:

```scala
environment.rules.stateAccessMetering match
  case StateAccessMetering.Settled  => Set.empty
  case StateAccessMetering.WarmCold =>
    val seeded = environment.rules.precompiles.addresses + ...
```

(`TransactionProcessor.scala:384-388`.) **Under `Settled` the warm set is
empty and the registered addresses are not an input to gas accounting at
all.** `Settled` is the base and `WarmCold` arrives at EIP-2929, Berlin.

**So this effect does NOT recur at every fork that adds a precompile, and in
this build most precompile-registering forks predate it** — EIP-196, EIP-197
and EIP-198 at Byzantium and EIP-152 at Istanbul are all `Settled`; EIP-4844
at Cancun, Prague's seven and Osaka's one are `WarmCold`. **This matters
family-neutrally rather than as a historical note: Ethereum Classic's schedule
is largely pre-Berlin.**

**Effect B — the unregistered-address behavior, at every fork.** `PrecompileSet.at`
returns an `Option`, so a `CALL` to an unregistered address does not fail — it
behaves as an ordinary empty account and **succeeds returning empty**. This is
fork-independent and is the half that holds everywhere.

**Two consequences of Effect A, and neither is visible from the proposal's
own text.**

- **A partial set is wrong twice, and the second way is the quiet one.** A
  `CALL` to an unregistered precompile does not fail — this build's
  `PrecompileSet.at` returns an `Option` and an unregistered address behaves as
  an ordinary empty account, so the call **succeeds returning empty**. That is
  the loud half. The quiet half is that every *other* transaction in the fork
  is now charged a different amount, because the warm seed is smaller. **So a
  fork's precompiles register together or not at all.**
- **Certifying a fork's whole corpus depends on the complete set**, including
  cases that touch no precompile. A corpus run with a partial set produces
  gas divergences whose cause is nowhere near the failing case.

**This is a standing check at every warm/cold fork that adds one, not a
Prague note — and one address is as disruptive as seven.** Prague adds seven
(`0x0b`–`0x11`, EIP-2537's BLS12-381 set) and **Osaka adds `0x100`**
(`P256VERIFY_ADDRESS`, `ethereum/execution-specs` @ `0cc100eb1`,
`src/ethereum/forks/osaka/vm/precompiled_contracts/__init__.py:55`), with the
same whole-fork effect. **At a `Settled` fork only Effect B applies**, so a
pre-Berlin precompile addition is a genuinely smaller change and must not be
sized as if Effect A were in play.

---

## The system-call seam

**A system call — an invocation a block makes on its own account, rather than
one a transaction it carries makes — is architecturally distinct from a
transaction in four ways. None of the four is optional for a future system
call to get right, because each was reached by a defect this build would
otherwise have shipped.** `org.fukuii.execution.SystemCall` is where all four
are implemented; this section states them as durable facts a reviewer checks
any new system call against, independent of that one file's own text.

### Three independent axes — and this section got the same thing wrong twice

**This section was titled "the pre-execution system-call seam" and stated
facts 2 and 3 as properties of system calls in general. Both were properties
of ONE system call — EIP-4788's — because it was the only one this build had
built.**

**Then the correction repeated the error.** The first version of this
subsection replaced "unchecked" with a single **checked** bit, read off
Prague, where the two refusal conditions happen to be coupled — and cited
Gnosis as the case that settled it, quoting `withdrawals.md:61` **and stopping
one line short of `:62`, which refutes the generalization.** Caught in review,
against the same file the citation came from.

**That is the durable lesson and it is worth more than either fact.** A
property read off the members you have looks identical to a property of the
category, and **correcting one such generalization is a moment of maximum
exposure to making another** — you are writing confidently, from fresh
evidence, about the exact kind of claim you just found to be over-general.
**Read the lines after the one that proves your point.**

A system call varies along three axes that do not move together:

- **WHERE it runs.** Before the transactions (EIP-4788's beacon root, and
  EIP-2935's history storage), or after them (Prague's withdrawal-request and
  consolidation calls, which run after withdrawals). The pre-execution
  position is the one this build built first; it is not the definition.
- **WHAT its outcome can refuse — and this is TWO conditions, not one bit.**
  A call may be refused because the **target holds no code**, and it may be
  refused because the **call itself failed**. They are separately decidable
  and the networks decide them separately.
- **WHAT it contributes to a commitment.** Nothing (EIP-4788 and EIP-2935
  write storage only, and Gnosis's withdrawal call), or a record derived from
  its return data (Prague's two, which feed `requests_hash`). Independent of
  the other axes: a checked call need not contribute, and a contributing call
  need not be checked.

**The refusal conditions are two, and reading them as one is how this section
was wrong a SECOND time.** Prague couples them — `process_checked_system_
transaction` raises on both — so Prague alone makes them look like a single
"checked" bit. **Gnosis decides them oppositely**, and the line that says so
is the line *after* the one this section first quoted:

| Call | Empty target | Execution failure |
|---|---|---|
| EIP-4788, EIP-2935 (unchecked) | tolerated | tolerated |
| Prague EIP-7002, EIP-7251 | **block invalid** | **block invalid** |
| Gnosis `WITHDRAWAL_CONTRACT` | **tolerated** | **block invalid** |

`gnosischain/specs` @ `045d46d6d`, `execution/withdrawals.md`: `:61` *"If the
transaction reverts, or runs out of the gas, the entire block **MUST** be
considered invalid."* — and `:62` *"If no contract is deployed at
`WITHDRAWAL_CONTRACT`, ignore this system call and allow the block to be
considered valid."* That second line was added deliberately, `047883e39`
(2024-12-10), *"Specify no contract case in withdraw contract"*.

**So an implementer who reads "checked ⇒ the block is invalid" and applies it
to Gnosis's call refuses a block Gnosis's own specification declares valid.**
Settle both conditions for the call in front of you; do not carry either from
another network.

**Position and refusal are still independent, which is what Gnosis shows
best**: it is a *withdrawal-time* call and it refuses on failure, so "refuses"
is not a property of the post-execution position.

**The executable specification names both kinds as separate functions**, which
is the clearest statement of the axis available: `ethereum/execution-specs` @
`0cc100eb1` `forks/prague/fork.py` defines
`process_unchecked_system_transaction` (`:641`) and
`process_checked_system_transaction` (`:582`), and `apply_body` (`:743-765`)
calls the unchecked form twice before the transactions and the checked form
twice inside `process_general_purpose_requests`, after withdrawals.

**A checked call's pre-check reads through a throwaway transaction state, so
it can see a system contract deployed earlier in the SAME block** — the
specification says so in its own comment at `:606-613`, naming EIP-7002 and
EIP-7251 as the edge case. A pre-check written against the pre-state instead
answers a different question and would refuse a valid block.

**That is measured, not reasoned, and the corpus that discriminates it is not
the one you would reach for.** The published deployment cases state it
directly: `deploy_after_fork-{zero,nonzero}_balance` expect
`SYSTEM_CONTRACT_EMPTY`, while **`deploy_on_fork_block-{zero,nonzero}_balance`
deploy the contract in the fork block itself and expect the block to be
VALID**. A pre-state pre-check refuses those. **No `for_prague` case can catch
it**, because the contracts are already at genesis there — the cases live only
under `for_cancuntopragueattime15k`
(`.../prague/eip7002/contract_deployment/system_contract_deployment.json`, and
the EIP-7251 equivalent). **So a transition label is load-bearing for a rule
that is not about the transition.**

**An empty return appends nothing, and absent is not empty.** Both checked
calls append a request only `if len(...return_data) > 0`
(`src/ethereum/forks/prague/fork.py:789-810`), so a call returning nothing
contributes no record — distinct from contributing an empty one, which would
change the commitment. `compute_requests_hash`
(`src/ethereum/forks/prague/requests.py:289-308`) hashes only the entries
present, which is *why* the distinction moves the header.

**And the requests list has THREE contributors, not two. The third is not a
system call at all, so checking "every new system call" never prompts it.**
`process_general_purpose_requests` appends, in this order
(`src/ethereum/forks/prague/fork.py:783-810`):

1. **Deposits, parsed from the block's own receipts** — `parse_deposit_requests`
   scans logs emitted by the deposit contract. No call is made. It carries its
   own block-invalidating refusals on malformed log geometry
   (`src/ethereum/forks/prague/requests.py:210`, `:225`, `:235`, `:247`, each
   `raise InvalidBlock`).
2. The withdrawal-request checked call's return data.
3. The consolidation checked call's return data.

**Order is normative** — the specification states *"Requests are to be in
ascending order of request type"* (`fork.py:783`) — so the list is not a set
and a correct-but-reordered list states a different commitment.

**This is the seam framing's own blind spot, recorded because it caused a real
miss:** a rule expressed as "check each new system call" cannot see a
contributor that is not a call. **Ask what feeds the commitment, then ask
which of those are system calls** — not the reverse.

**1. It is a named seam handed the EVM, not a state view.** A system call
invokes code at an address, so it has to run through the actual interpreter
machinery — a `Frame`, a `Message`, an `Environment` — rather than through a
narrower "write this value into state" interface. `SystemCall.run` builds a
real `Frame`/`Message`/`Environment` and drives `Interpreter.run` with them,
exactly as a transaction does, which is what makes `CALLER`, `TIMESTAMP` and
(per Instance 2 above) `GASPRICE` all live operations inside one. A
state-view interface could not have run the deployed EIP-4788 contract's own
`CALLER`/push/`EQ` branch at all.

**2. It must be able to report an unbuilt operation as a value, not discard
it.** An unbuilt operation is one the fork's table admits and this build does
not yet run, which is **not a chain result at all** — so it can never be
collapsed into either "the call succeeded" or "the block is invalid".
**A `Unit`-returning call site discards that channel silently**, and a caller
that discarded it would be recording an unbuilt operation as a state this
network reached — which is exactly the "honest domain answer" a
`Some(Unsupported)`/`None` return exists to prevent. This is also why a system
call is never modeled as `BlockProcessor`'s
`irregularStateChange: WorldState => Unit`: that shape has nowhere to put the
signal, and its own contract is a change a schedule knows about for one
block, where a system call runs on every block from its fork onward.

**This fact holds for both kinds, and the reason differs between them.** For
an **unchecked** call the return channel is the *only* thing it can report:
EIP-4788's own words are that *"the call must execute to completion"* and
*"the call does not count against the block's gas limit"* (`ethereum/EIPs @
d2a64c2d4`, `EIPS/eip-4788.md`, Final), so nothing it does makes a block
invalid. For a **checked** call the channel must stay distinct from the
refusal — an unbuilt operation is not a failed call, and answering the domain
refusal for it would invalidate a block this build simply cannot judge.

> **CORRECTED. This fact previously ended its first sentence with *"— a system
> call has no refusal and cannot invalidate a block"*, stated of system calls
> in general.** That is true of EIP-4788 and **false of Prague's checked
> calls**: `process_checked_system_transaction` raises `InvalidBlock` both when
> the target holds no code and when the call returns any error
> (`ethereum/execution-specs` @ `0cc100eb1`,
> `src/ethereum/forks/prague/fork.py:582-638`), and the published corpus states
> both refusals by name — `SYSTEM_CONTRACT_EMPTY` and
> `SYSTEM_CONTRACT_CALL_FAILED` (`ethereum/execution-specs-fixtures` @
> `tests-v20.0.1`). **A reviewer who applied the old wording to EIP-7002 or
> EIP-7251 would have built a call that accepts a block every other client
> rejects**, which is a chain split rather than a missing feature.
>
> **The retracted sentence is kept verbatim above because it is the search key
> for the source that still carries it.** One file does:
> `org.fukuii.execution.SystemCall`'s scaladoc, where it justifies an absent
> type — *"which is why nothing here returns a `BlockRejection`"*. **That is
> the design the post-execution seam changes, so the sentence and the refusal
> channel move together** rather than the comment being corrected ahead of the
> code. `org.fukuii.execution.Withdrawals` carried a sibling claim and **was
> scoped at this correction instead**, because its falsifier is Gnosis rather
> than Prague and no Prague-era change would have reached it.

**3. An UNCHECKED call's writes commit on every outcome, and that is a
property of the OUTCOME check, not of what an individual invocation keeps.**
Point 2's specification is the proposal; here it is the **executable**
specification, `ethereum/execution-specs`, and it runs the call *"without
checking if the contract contains code or if the transaction fails"* (@
`0cc100eb1` `forks/cancun/fork.py:552-553`), and `ethereum/go-ethereum @
02872e9ef` `core/state_processor.go:336` discards all three results of its
call — `_, _, _ = evm.Call(...)` — and finalizes immediately after, in sharp
contrast with the ordinary system-contract call thirty lines below it, which
panics on error. **This does NOT mean a reverted invocation's own writes
survive.** The interpreter still undoes those inside itself, exactly as for a
transaction. What is unchecked is whether the caller branches on success or
failure before committing whatever the journal holds — which, after a
revert, is nothing new. Reading "committed on every outcome" as "reverted
writes are kept" is the misreading this fact exists to prevent.

**A refusing call does NOT change this, and the mechanism is the part worth
knowing.** The executable specification implements the checked form as a
**wrapper over the unchecked one**: `process_unchecked_system_transaction`
commits **unconditionally** — `incorporate_tx_into_block(system_tx_state)` at
`fork.py:706`, no branch — and `process_checked_system_transaction` inspects
`.error` **afterwards**, at `:632`, raising then. **So the writes commit and
the block is discarded after**, rather than the commit being skipped.

**Observationally identical, structurally not, and the difference is a trap
for the natural implementation.** A build that adopts the same
wrapper-over-primitive shape and "helpfully" makes the commit conditional in
the shared primitive silently breaks the **unchecked** case, where the
unconditional commit is load-bearing. Fact 3 is a property of the primitive;
a refusal is a check layered above it.

**So this fact's literal claim survives Prague** — writes still commit on
every outcome — and what Prague adds is a check that runs after. The
correction is that the fact was stated of system calls in general while
describing the primitive; a reader must not conclude from it that a refusing
call has no commit to reason about.

**4. Reusing the ordinary call path leaves an empty account at the target,
and nothing downstream removes it.** The interpreter brings the account it
runs as into existence before any code runs, and keeps it where the
invocation ends normally — correct for a transaction, because
`TransactionProcessor` offers every account a transaction touched to
EIP-161's clearing rule when it settles. **A system call settles no
transaction, so nothing offers its accounts to that rule.** Without a
dedicated check, this build would commit an empty account to the trie, and a
state root carrying one is a chain split against every client surveyed. All
four implementations read for this — `besu-eth/besu`, `ethereum/go-ethereum`,
`NethermindEth/nethermind`, `ethereum/execution-specs` — leave no account
where the target holds no code, each reaching that state by a different
mechanism (an exception caught before commit, a zero-value early return under
EIP-158, a null check before executing, and a suppressed value transfer with
no `touch_account` since Paris respectively). **The deployed case hides this
completely**, because the account is already there; the defect is visible
only on a network whose contract was never deployed. **That case is not
hypothetical**: `consensus-poa.md` makes a network this project authors
itself a scheduled stage, and an authored genesis holds no beacon-roots
contract unless this client puts one there at genesis. `EIPS/eip-4788.md`
anticipates the same failure from the other direction, warning that omitting
the call in favor of writing storage directly *"could be problematic on
non-mainnet situations in case a different contract is used."*

**Any future system call — a second beacon-root-shaped write, a withdrawal or
deposit-processing contract call, or whatever a later proposal adds — is
checked against all four of these before it is trusted, not only against its
own proposal's text.** A system call that skips fact 4 because "the mainnet
contract is always deployed" is correct on mainnet and wrong on the first
network this project stands up itself.

**Before applying any of the four, settle the three axes above for the call in
front of you.** Which fact applies is not uniform:

| Fact | Applies to |
|---|---|
| **1** — a named seam handed the EVM | every system call |
| **2** — an unbuilt operation is a value, not a refusal | every system call, for different reasons per kind |
| **3** — writes commit whatever the outcome | stated of the unchecked kind; see below for what a refusal adds |
| **4** — no empty account left at the target | **only where an empty target is tolerated** |

**Fact 4's applicability is decided by the empty-target column, and that is
the opposite of what "checked" suggests.** Fact 4 exists because a call whose
target holds no code must leave no account behind — a state root carrying one
is a chain split. So it applies exactly where an undeployed target is
*tolerated*:

- **EIP-4788, EIP-2935 (tolerated):** fact 4 applies in full.
- **Prague's EIP-7002, EIP-7251 (refused):** fact 4 is **pre-empted, not
  satisfied**. The pre-check raises at `fork.py:620-624` **before**
  `process_unchecked_system_transaction` is entered at `:626`, so the
  interpreter never runs and no account is created. There is no empty account
  to avoid.
- **Gnosis's withdrawal call (tolerated):** fact 4 applies again, in full.

**A protocol that said "facts 1 and 4 hold for both kinds" would be wrong in
the middle row**, and the earlier draft of this section said exactly that, one
paragraph above a paragraph saying the opposite.

**What survives across all three rows is the genesis obligation, reached from
two directions.** Where an empty target is tolerated, an undeployed contract
is a silent state-root defect. Where it is refused, an undeployed contract is
a network that **cannot produce a valid block at all**. **So a network this
project stands up itself must deploy the contracts its schedule activates, or
not activate them** — and that holds whichever way the refusal went.

---

## Evidence weight

**The litmus is policy, and policy does not expire — it is revised by the
operator, not superseded by a reading of an external source.** The tiebreak
rule and both worked instances are grounded in this build's own reviewed,
citation-backed implementation, and every citation in this file was
independently re-resolved against the reference corpus rather than trusted
on the strength of appearing in already-merged code. **This file's sections no
longer share one evidence standing, and the differences are material enough to
state per section rather than as one disclaimer.**

**Transcribed** — the refs were confirmed to resolve to the commits claimed,
and what the citing code already established was carried across, without
re-opening the source line by line: **the tiebreak rule itself and its two
worked instances**, drawn from `JournaledWorldState.scala` and
`SystemCall.scala`.

**Read directly against the reference corpus, and therefore stronger than the
rest of this file:**

- **Instance 1's `NethermindEth/nethermind` and `erigontech/erigon`
  readings**, added during a review pass that widened the survey past the two
  clients `JournaledWorldState.scala`'s scaladoc already cited. Neither
  client's marker behavior was cited anywhere in this build before that pass.
- **The whole system-call seam correction of 2026-09-17** — the three axes,
  the empty-target column, the fact-4 applicability table, fact 3's
  commit-then-raise mechanism, the requests-list contributors and the
  pre-check's published discriminator — read from
  `ethereum/execution-specs` @ `0cc100eb1`, the fixture release at
  `tests-v20.0.1` and `gnosischain/specs` @ `045d46d6d`.
- **The which-text-governs section**, read from `ethereum/EIPs` at the named
  commits and `ethereum/go-ethereum` `params/config.go`.
- **The lineage root-commit measurement** in the clients-diverge section.

**Mostly a consolidation of this build's own code, and the one section the
header's "verified against primary sources" does NOT describe: the
precompile-registration section.** Its load-bearing evidence is
`TransactionProcessor.scala:384-388` and `PrecompileSet.at`; only Osaka's
`0x100` is a specification read.

**Restated once because it was wrong here before**: "four implementations" in
the instances below is a count of code read, not of independent lineages —
go-ethereum and erigon share a root commit. Treat the tiebreak's two worked
instances with the same standing their source code carries, no stronger:
`markAccountCreated`'s reading is corroborated by 259 published cases and a
stated derivation; `SystemCall.GasPrice` is UNSETTLED by design and carries
its own reversing trigger. Neither is a value to be "corrected"
back toward agreement between the specification and the clients — that
agreement does not exist today, and the tiebreak is what to do in its
absence, not a prediction of what it will be.
