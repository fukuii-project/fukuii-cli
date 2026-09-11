# Consensus change — the litmus, the tiebreak, and the system-call seam

**provenance:** the state-root litmus is this project's own routing policy —
decided by the operator, stated in `.claude/agents/forge.md` and
`.claude/agents/banksy.md`, and not an external fact that needs verifying
against a specification. **The unobservable-divergence tiebreak and the
system-call seam facts are drawn from this build's own implementation** —
`org.fukuii.execution.SystemCall` and `org.fukuii.evm.JournaledWorldState` —
which itself cites the executable specification and the production clients at
refs that cannot move. This file consolidates what those two already state and
cite; it is not a fresh survey, and a citation repeated here is only as current
as the source it was drawn from. Every ref below was independently resolved
against this project's reference corpus, 2026-09-11 — not merely copied.

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

## The pre-execution system-call seam

**A system call — the invocation a block makes on its own account before any
transaction it carries has run, EIP-4788's beacon-root write being the first
instance this build has built — is architecturally distinct from a
transaction in four ways. None of the four is optional for a future system
call to get right, because each was reached by a defect this build would
otherwise have shipped.** `org.fukuii.execution.SystemCall` is where all four
are implemented; this section states them as durable facts a reviewer checks
any new system call against, independent of that one file's own text.

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
it.** The specification's own words are that *"the call must execute to
completion"* and *"the call does not count against the block's gas limit"*
(`ethereum/EIPs @ d2a64c2d4`, `EIPS/eip-4788.md`, Final) — a system call has
no refusal and cannot invalidate a block. The one thing it CAN report is an
operation the fork's table admits and this build does not yet run, which is
not a chain result at all. **A `Unit`-returning call site discards that
channel silently**, and a caller that discarded it would be recording an
unbuilt operation as a state this network reached — which is exactly the
"honest domain answer" a `Some(Unsupported)`/`None` return exists to prevent.
This is also why a system call is never modeled as `BlockProcessor`'s
`irregularStateChange: WorldState => Unit`: that shape has nowhere to put the
signal, and its own contract is a change a schedule knows about for one
block, where a system call runs on every block from its fork onward.

**3. Its writes commit on every outcome, and that is a property of the
OUTCOME check, not of what an individual invocation keeps.** Point 2's
specification is the proposal; here it is the **executable** specification,
`ethereum/execution-specs`, and it runs the call *"without checking if the
contract contains code or if the transaction fails"* (@ `0cc100eb1`
`forks/cancun/fork.py:552-553`), and `ethereum/go-ethereum @ 02872e9ef`
`core/state_processor.go:336` discards all three results of its call —
`_, _, _ = evm.Call(...)` — and finalizes immediately after, in sharp
contrast with the ordinary system-contract call thirty lines below it, which
panics on error. **This does NOT mean a reverted invocation's own writes
survive.** The interpreter still undoes those inside itself, exactly as for a
transaction. What is unchecked is whether the caller branches on success or
failure before committing whatever the journal holds — which, after a
revert, is nothing new. Reading "committed on every outcome" as "reverted
writes are kept" is the misreading this fact exists to prevent.

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

---

## Evidence weight

**The litmus is policy, and policy does not expire — it is revised by the
operator, not superseded by a reading of an external source.** The tiebreak
rule and both worked instances are grounded in this build's own reviewed,
citation-backed implementation, and every citation in this file was
independently re-resolved against the reference corpus rather than trusted
on the strength of appearing in already-merged code. **What is not re-derived
here, with one exception, is the underlying specification and client
readings themselves** — this file did not re-open `ethereum/execution-specs`,
`ethereum/go-ethereum`, `besu-eth/besu`, `NethermindEth/nethermind` or
`erigontech/erigon` line by line; it verified that the refs cited resolve to
the commits claimed and transcribed what the citing code already established.
**The exception is Instance 1's `NethermindEth/nethermind` and
`erigontech/erigon` readings**, added during a review pass that widened the
survey past the two clients `JournaledWorldState.scala`'s own scaladoc
already cited. Neither client's marker behavior was cited anywhere in this
build before that pass, so both were read directly against the reference
corpus rather than transcribed — a stronger form of evidence than the rest of
this file carries, not a weaker one, and worth distinguishing rather than
folding into the general disclaimer. Treat the tiebreak's two worked
instances with the same standing their source code carries, no stronger:
`markAccountCreated`'s reading is corroborated by 259 published cases and a
stated derivation; `SystemCall.GasPrice` is UNSETTLED by design and carries
its own reversing trigger. Neither is a value to be "corrected"
back toward agreement between the specification and the clients — that
agreement does not exist today, and the tiebreak is what to do in its
absence, not a prediction of what it will be.
