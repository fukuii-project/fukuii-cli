package org.fukuii.evm

import org.fukuii.bytes.{Address, UInt64}

/** A change one proposal makes to the rules a chain runs.
  *
  * A function rather than a record of what changed, because the changes are not
  * one shape: an addition to the operation table, a price moved in the schedule
  * and a behavior settled by a flag have nothing in common except that each
  * turns one set of rules into another.
  */
type Proposal = EvmRules => EvmRules

/** How much of what a caller has left a nested invocation is given.
  *
  * ==Data rather than a function, and that is what makes rules comparable==
  *
  * This was a `(BigInt, BigInt) => BigInt`, which is the shape the arithmetic
  * suggests and the wrong shape for the thing it sits in. Two consequences, and
  * the second is the one that bites:
  *
  *   - **Equality on a function is unspecified.** A lambda capturing nothing
  *     may or may not be the same instance from one evaluation to the next, so
  *     [[EvmRules]] could not be compared as a whole while one of its members
  *     was one -- and *"do these two networks run the same rules"* is a
  *     question this project has to answer.
  *   - **A later proposal could replace this rule by accident.** Written
  *     `_.copy(gasForwarded = ...)`, a body that ignores what it found is a
  *     REPLACEMENT and a body that calls it is a REFINEMENT, and nothing at the
  *     call site distinguishes them. As data, a proposal that wants to refine
  *     rather than replace cannot express itself without adding a case here,
  *     which is a deliberate act rather than a silent one.
  *
  * ==Two arguments, and the whole rule ignores the first==
  *
  * `remaining` is what the caller would still hold once the invocation's own
  * price and its memory are paid; `requested` is what the operation asked for.
  * A rule that caps nothing cannot be written as the identity over `remaining`
  * -- that would compare the request against what is left and hand back the
  * smaller, turning a caller that asks for more than it holds from a frame that
  * runs out of gas into one that quietly succeeds with less. **The comparison
  * exists only where a cap does**, so a rule able to ignore `remaining`
  * outright is what the second argument buys. besu's uncapped gas calculator
  * reaches the same conclusion from the other side: it hands back the request
  * and compares nothing.
  *
  * ==Contract==
  *
  * `remaining` is never negative. Its callers subtract a price from what a
  * frame holds, and a frame that cannot cover that price passes zero rather
  * than the difference -- so the clamp has one home, at the subtraction, rather
  * than one here and one there.
  *
  * Both arguments are gas, so transposing them compiles and is wrong. The order
  * is `(remaining, requested)`.
  *
  * ==Postcondition: `0 <= result <= remaining`==
  *
  * A rule may hand back no more than the caller has left. Both cases this build
  * ships satisfy it by construction -- one returns its second argument, which
  * its caller has already bounded, and the other takes a minimum against it --
  * so nothing turns on it today. **The reason to state it is the shape of the
  * rule that would break it**: a floor, of the kind that gives a callee a
  * minimum to work with, returns a figure that does not depend on `remaining`
  * at all, and extending this enum is exactly what such a proposal would do.
  *
  * Both sites enforce it rather than trusting it, and neither could be told to
  * do otherwise: each charges what it was handed, and a charge refuses rather
  * than taking a frame's gas below nothing.
  */
enum GasForwarding:

  /** The invocation is given what it asked for, whatever the caller has left.
    *
    * A request larger than the caller can cover is then a frame that runs out
    * of gas rather than one that succeeds with less.
    */
  case Whole

  /** The invocation is given what it asked for, capped at all but one
    * sixty-fourth of what the caller has left.
    *
    * ==The name and the figure are both the ecosystem's==
    *
    * `besu-eth/besu` @ `c2addd9424` supplies the name --
    * `allButOneSixtyFourth(value) = value - value / 64` -- and
    * `ethereum/go-ethereum` @ `6bb0588ad` and `ethereumclassic/core-geth` @
    * `4185df450` write the same arithmetic as `gas - gas/64` in
    * `core/vm/gas.go`. **None of those three parameterizes the fraction**, and
    * neither does this.
    *
    * ==A network that chose differently adds a case==
    *
    * That is a deliberate act, which is the property this type is already built
    * around: a proposal cannot refine what a predecessor set without extending
    * the enum, and a network wanting another fraction is the same shape of
    * change. Every such case arrives with its own arithmetic written out, so
    * there is no quantity here for a caller to supply and none to be wrong.
    *
    * **That is what makes the invalid state unrepresentable rather than
    * guarded.** A held divisor would be a bare `Int` in a consensus slot, and
    * no scoping of its constructor closes structural construction -- so the
    * value would stay reachable however carefully the constructor were
    * restricted, and the guard would be a claim to keep true rather than a
    * property the compiler enforces.
    */
  case AllButOneSixtyFourth

  /** What a nested invocation is given, out of what it asked for. */
  def forward(remaining: BigInt, requested: BigInt): BigInt = this match
    case Whole                => requested
    case AllButOneSixtyFourth => requested.min(remaining - remaining / 64)

/** How a store to a storage slot is priced.
  *
  * ==Three cases, and one network's history reaches all three==
  *
  * EIP-1283 replaces the whole `SSTORE` charge with a scheme that reads what
  * the slot held at the start of the transaction, so that repeated writes to
  * one slot are not repeatedly charged as though each were the first. EIP-1716
  * removes it again. EIP-2200 restores it with one rule added, and that rule is
  * what [[NetWithSentry]] carries.
  *
  * **On Ethereum mainnet the first two activate at the same block**, so the net
  * scheme was never in force there. It WAS in force on Ropsten, Kovan and
  * Rinkeby for hundreds of thousands of blocks each. **Gnosis reaches every one
  * of the three**: `gnosischain/configs` @ `2dd5746`, `mainnet/genesis.json`
  * sets `eip1283Transition` at 1,604,400, `eip1283DisableTransition` at
  * 2,508,800, and `eip1283ReenableTransition` beside `eip1706Transition` at
  * 7,298,030. So this is a rule networks genuinely differ on, and the third
  * case is not Ethereum's alone.
  *
  * ==Why a case on the rules rather than a table entry==
  *
  * `SSTORE` is priced from its operands and carries `Cost.Computed`, so there
  * is no entry to swap. `ethereum/go-ethereum` @ `e9e35a42f8` reaches the same
  * shape by a runtime predicate (`core/vm/gas_table.go:109`), and
  * `besu-eth/besu` @ `fdf1247c6d` by swapping a whole gas calculator. A value
  * on the rules is the smallest thing that expresses either.
  *
  * ==Why three cases and not two fields==
  *
  * The sentry could be a second, orthogonal member -- a scheme beside a
  * threshold. That would make four states representable, and one of them,
  * legacy pricing with a sentry, is run by no network and is expressible by no
  * surveyed client: `ethereumclassic/core-geth` @ `4185df450` places its
  * `eip1706Transition` after the legacy early return, so it cannot be reached
  * there either. An enum admits exactly the three that exist.
  *
  * A threshold on this value would also be a number in a slot that already has
  * an owner. Every figure the three cases need is [[GasSchedule]]'s, the
  * sentry's included -- see [[NetWithSentry]].
  */
enum StorageMetering:

  /** Priced from what the slot holds now: setting a slot that held nothing is
    * the expensive case, and every other combination is the cheaper one.
    */
  case Legacy

  /** Priced from what the slot held at the START OF THE TRANSACTION as well as
    * what it holds now -- EIP-1283's no-op, fresh and dirty cases.
    */
  case Net

  /** [[Net]]'s clauses, refused outright when the invocation has too little gas
    * left to be worth entering.
    *
    * ==One rule, and everything else is the schedule's==
    *
    * EIP-2200 § *Specification*: *"If gasleft is less than or equal to gas
    * stipend, fail the current call frame with 'out of gas' exception"*
    * (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-2200.md`, Final). That is a new
    * failure condition reading an operand -- what the frame has left -- that
    * [[Net]]'s clauses never read. The nine clauses below it are EIP-1283's,
    * unchanged in structure, which is why both cases charge through one helper.
    *
    * **The threshold is [[GasSchedule.callStipend]] and not a figure of its
    * own.** EIP-2200 does not list the stipend among the four variables it
    * defines, naming it instead as *"the gas stipend given to
    * 'transfer'/'send'"* -- the amount a paid call is guaranteed. Both networks
    * here already set it to 2,300, and four of six surveyed lineages encode the
    * sentry as that same constant rather than minting a second one.
    *
    * ==A network reaches this without adopting EIP-2200==
    *
    * EIP-2200 is a combination -- of EIP-1283, EIP-1706 and a repricing -- and
    * this case carries only the first two. Gnosis composes it from the parts,
    * turning EIP-1283 back on at the block it adds EIP-1706 and naming EIP-2200
    * nowhere; the repricing that document also carries is separately the
    * schedule's. So the case is what a network switched on, never which
    * document it read.
    */
  case NetWithSentry

/** How reaching an account, or a storage slot, is priced.
  *
  * ==Why a case on the rules, when four of the eleven operations could have
  * carried it in the table==
  *
  * `BALANCE`, `EXTCODESIZE`, `EXTCODEHASH` and `SLOAD` are priced from a
  * [[Cost.Fixed]] entry under [[Settled]] and work out their own charge under
  * [[WarmCold]], so for those four the table entry could say which scheme is in
  * force. **The other seven cannot**: `EXTCODECOPY`, the four call forms,
  * `SELFDESTRUCT` and `SSTORE` already carry [[Cost.Computed]] under both
  * schemes, and a constructor carrying no operand cannot distinguish two ways of
  * computing. A rule read by all eleven is the smallest thing that reaches every
  * one of them.
  *
  * **The four entries move to [[Cost.Computed]] anyway**, and that is not a
  * second switch. [[Cost.Fixed]] means the charge *"depend[s] on nothing but
  * which operation it is"*, which under [[WarmCold]] is false -- so leaving a
  * figure there would be exactly the *"figure standing where a computation
  * belongs"* [[Cost]] exists to make impossible. The entry states what the table
  * knows; this states how the operation works it out.
  *
  * ==What varies is only the charge, never what the operation does==
  *
  * Both cases push the same value and read the same state. That is what keeps
  * this a price rather than a behavior, and it is why the sets the second case
  * reads live on [[Frame]] and not here: a mutable per-transaction set on a
  * record whose whole purpose is value comparison would make two identical
  * configurations compare unequal.
  */
enum StateAccessMetering:

  /** Every reach costs the same, whether or not this transaction has made it
    * before.
    *
    * The account-reading operations are priced from their table entries and the
    * call family from [[GasSchedule.callBase]], so nothing about what the
    * transaction has already touched is read at all.
    */
  case Settled

  /** The first reach at an account or a slot within one transaction costs more
    * than every later reach at the same one.
    *
    * ==The three figures are the schedule's and the two sets are the frame's==
    *
    * [[GasSchedule.warmAccess]], [[GasSchedule.coldAccountAccess]] and
    * [[GasSchedule.coldStorageAccess]] are what this case spends;
    * [[Frame.accessedAddresses]] and [[Frame.accessedStorageKeys]] are what it
    * asks. Neither is here, for the reason the type's own note gives.
    *
    * ==The eleven operations do not all read it the same way, and two are
    * exceptions the document states==
    *
    * `SELFDESTRUCT` adds the cold figure where its beneficiary is cold and adds
    * NOTHING where it is warm, rather than paying the warm figure -- *"`SELFDESTRUCT`
    * does not charge a `WARM_STORAGE_READ_COST` in case the recipient is already
    * warm, which differs from how the other call-variants work"* (`ethereum/EIPs`
    * @ `dbfa6bee8`, `EIPS/eip-2929.md`, Final). `SSTORE` likewise adds the cold
    * storage figure as a prefix to a charge it computes by its own scheme,
    * rather than replacing it.
    *
    * The call family REPLACES [[GasSchedule.callBase]] rather than adding to it,
    * and the substitution happens before the forwarded request is worked out:
    * *"the `100`/`2600` cost is applied immediately (exactly like how `700` was
    * charged before this EIP), i.e: before calculating the `63/64ths` available
    * for entering the call"* (same document). An implementation that added the
    * two, or that substituted after the split, would forward a different figure
    * and reach a different state root.
    *
    * ==`CREATE` and `CREATE2` pay nothing here and still write to the set==
    *
    * *"gas costs of `CREATE` and `CREATE2` are unchanged"*, and the address
    * being created is added *"immediately (ie. before checks are done to
    * determine whether or not the address is unclaimed)"*. So a creation that
    * fails leaves its own address warm, which is the one place the set outlives
    * the invocation that wrote it.
    */
  case WarmCold

/** When an operation pays the surcharge for bringing its destination into
  * being.
  *
  * ==Data rather than a boolean, for the reason [[GasForwarding]] is==
  *
  * The two readings do not differ by a threshold. One asks a single question of
  * the destination; the other asks a different question of the destination AND
  * a question about the operation, so they read different state and take
  * different arguments. A flag would leave that difference at the two sites
  * that levy the charge, where a proposal moving it could be applied at one and
  * not the other with nothing naming both.
  *
  * ==A network that read it a third way adds a case==
  *
  * That is the same deliberate act [[GasForwarding]] requires, and it is what
  * keeps the machine free of a quantity a caller could get wrong: neither case
  * below carries a number, so the surcharge itself stays the schedule's.
  */
enum NewAccountCharge:

  /** The destination is an account this state has never held.
    *
    * What the operation carries makes no difference: a call sending nothing to
    * an address nothing has used still brings an account into being, and pays
    * for it.
    */
  case WhenTheDestinationIsAbsent

  /** The operation moves value, and the destination is *dead* -- either
    * non-existent, or existing and holding nothing.
    *
    * ==Both halves changed at once, and neither is a repricing==
    *
    * EIP-161(b): *"whereas `CALL` and `SUICIDE` would charge 25,000 gas when the
    * destination is non-existent, now the charge SHALL only be levied if the
    * operation transfers more than zero value and the destination account is
    * dead"* (`ethereum/EIPs` @ `96523ef4d`, `EIPS/eip-161.md`, Final). The
    * figures do not move; what a network sets them to stays
    * [[GasSchedule.newAccount]]'s and [[GasSchedule.selfDestructNewAccount]]'s.
    *
    * Three implementations state the same pair. The executable specification
    * writes the call side as `if value == 0 or is_account_alive(state, to)` and
    * the destruction side as `not is_account_alive(beneficiary) and
    * get_account(current_target).balance != 0`
    * (`forks/spurious_dragon/vm/instructions/system.py` at `ccaaaba58`);
    * `ethereum/go-ethereum-pow` @ `v1.10.26` writes
    * `transfersValue && evm.StateDB.Empty(address)` and
    * `evm.StateDB.Empty(address) && evm.StateDB.GetBalance(contract.Address()).Sign() != 0`
    * (`core/vm/gas_table.go`); `besu-eth/besu` @ `c2addd9424` writes
    * `recipient == null || recipient.isEmpty()` under a zero-value early return,
    * and the same predicate against a non-zero inheritance
    * (`SpuriousDragonGasCalculator.java`).
    *
    * **What a destruction moves is the whole balance of the account ending**,
    * which is why its half of the condition is that balance rather than an
    * operand. All three sources read it there.
    *
    * ==It also settles clause (c), which is why nothing here refuses to create==
    *
    * The same document forbids an account changing state from non-existent to
    * existent-but-empty. The executable specification does not implement that
    * as a refusal: `state_tracker.py` is byte-identical across the two forks and
    * still creates, and the fork satisfies the clause by creating the account
    * and deleting it again at the end of the transaction -- the
    * invariant-preserving alternative the proposal's own title names. This case
    * is what makes the two shapes agree in the meantime: the surcharge is the
    * only price in the machine that could tell an absent destination from an
    * empty one, and under this reading it cannot.
    */
  case WhenValueReachesADeadDestination

/** The rules one chain runs, as a value a fork produces rather than a branch the
  * machine takes.
  *
  * ==A fork is a value here, and asking which fork is active is the failure==
  *
  * [[OpcodeTable]] already states this for operations -- *"nothing here asks
  * which fork is active, because by the time a table exists that question has
  * been answered"* -- and a behavior that varies by fork is the first thing that
  * would have broken the commitment, because a table cannot hold it and a
  * schedule cannot price it. So it is held here, beside them, and the machine
  * reads a value exactly as it reads the other two.
  *
  * ==The field settled the representation, and the clients disagree==
  *
  * Surveyed for the first behavior change this had to carry, a creation that
  * cannot pay to store its code:
  *
  *   - the executable specification duplicates the whole fork package;
  *   - go-ethereum and its proof-of-work line branch inside the machine on a
  *     fork-named boolean, `chainRules.IsHomestead`;
  *   - core-geth branches inside the machine too, but on a per-proposal
  *     predicate, `IsEnabled(GetEIP2Transition, blockNumber)`, because it serves
  *     several networks from one binary;
  *   - besu carries a `requireCodeDepositToSucceed` field on a processor its
  *     fork constructs, and branches nowhere.
  *
  * **The last is the shape adopted**, because it is the only one that leaves the
  * machine free of fork names -- which is the commitment above -- and because
  * this project already hands the machine its operations and its prices as
  * values, so a behavior is the third of a kind rather than a new mechanism.
  * **core-geth's vocabulary is adopted with it**: a flag is named for the rule
  * it settles, never for the fork that shipped it, so the multi-network reading
  * stays available.
  *
  * ==Deltas, and why they must not rewrite what they do not name==
  *
  * A fork is a network's starting configuration with a sequence of proposals
  * applied. That shape is core-geth's, which starts from a base instruction set
  * and applies a flat sequence of per-proposal activations, keeping fork names
  * only in comments. **A proposal that alters anything it does not name is the
  * seam failing**, and the fields left alone survive as the same values rather
  * than as equal copies -- which is what makes that testable rather than merely
  * intended.
  *
  * **What the starting configuration IS does not live here.** The machine holds
  * no privileged set of rules: a network's genesis configuration is that
  * network's, and this module would otherwise be handing every later network a
  * first network's choices to be a delta from.
  *
  * ==Comparing two of these IS a value comparison, and each member had to earn
  * that==
  *
  * *"Do these two networks run the same rules"* is a question this project has
  * to answer -- two networks sharing a history agree through the fork where
  * they part, and that is a test rather than a comment. A record answers by
  * value only when every member does, and three of these did not: two plain
  * classes with no equality of their own, and a function, whose equality the
  * language does not settle at all.
  *
  * All three were fixed rather than worked around, because the alternative was
  * a comparison that answered by value on some members and by reference on
  * others -- which is worse than one that answers by reference throughout. Such
  * a comparison returns *different* for two identical configurations built
  * separately, and *same* for two references to one build, so its answer is
  * decided by how a caller happened to construct its inputs.
  * [[PrecompileSet.equals]] carries the one residual and why its direction is
  * the safe one.
  *
  * **The seam's own claim is still asserted with `eq`, deliberately.** That a
  * proposal leaves untouched fields as the SAME value rather than an equal copy
  * is a stronger statement than equality, and only reference identity says it.
  *
  * @param gasForwarded
  *   how much of what the caller has left a nested invocation is given, out of
  *   what it asked for. Where a network caps nothing the invocation is given
  *   what it asked for and the caller pays for all of it, so a request larger
  *   than the caller can cover is a frame that runs out of gas. EIP-150 caps
  *   it, and the same request then succeeds with less. It reaches every nested
  *   invocation, `CREATE` included.
  * @param codeDepositMustSucceed
  *   whether a creation that cannot pay to store its returned code fails. Where
  *   a network has not adopted EIP-2 it does not: the account is left with no
  *   code, the gas already spent stays spent, and the creating operation is told
  *   the address as though code had been stored. EIP-2 reverses this.
  * @param maxCodeSize
  *   the longest deployed code a creation may leave behind, where the network
  *   bounds it at all. A creation returning more than this fails; a creation
  *   returning exactly this succeeds, the proposal's comparison being strictly
  *   greater. `None` is a network that bounds nothing, which is every height
  *   before EIP-170.
  *
  *   ==Absence is `None` rather than a saturating value, and the field is split==
  *
  *   Two clients hold the bound as a number that saturates: `NethermindEth/nethermind`
  *   @ `c35ce1b1ab` sets `spec.MaxCodeSize = long.MaxValue` at its earliest fork
  *   and `openethereum/openethereum` @ `v3.0.1` returns `u64::MAX` from
  *   `CommonParams::max_code_size` below the transition. Three hold a real bound
  *   and gate the comparison on something else -- `ethereum/go-ethereum` @
  *   `6bb0588ad8` on `rules.IsEIP158` inside `CheckMaxCodeSize`,
  *   `besu-eth/besu` @ `c2addd9424` on whether a `MaxCodeSizeRule` is in the
  *   fork's validation list at all, `bluealloy/revm` @ `3064c0901c` on a
  *   `SpecId` test beside the comparison.
  *
  *   **The gating shape is unavailable here and that is by construction**: these
  *   rules are what a fork resolved TO, so nothing holding them may ask which
  *   fork is active. That leaves the saturating value and this one, and a
  *   saturating value would be a bound no code can reach standing in for a bound
  *   that does not exist -- a reader has to know the sentinel to read the field,
  *   and `2 * maxCodeSize`, which EIP-3860 derives, is meaningless over it.
  *
  *   ==Not a flag beside a number, and not a case==
  *
  *   nethermind carries both -- `LimitCodeSize` and `MaxCodeSize` -- which admits
  *   a bound that is set and disregarded. [[GasForwarding]] records why this
  *   record does not do that.
  *
  *   It is nevertheless a NUMBER here where forwarding is a case, and the
  *   asymmetry is the field's rather than a preference. None of the three
  *   clients read for [[GasForwarding]] parameterizes the forwarding fraction;
  *   four values of this one are already in the field -- `0x6000` at EIP-170,
  *   `0xC000` at EIP-7907, `0x10000` at EIP-7954, and unbounded -- and two of
  *   the five named above read it from a chain configuration rather than from
  *   their own source. A case per value would
  *   have to be extended by a proposal that only moves a number.
  * @param createdAccountNonce
  *   the transaction count an account is given when it is created, before the
  *   code that initializes it runs. Zero is the count an account has by simply
  *   existing, so a network setting that here creates nothing it would not have
  *   created anyway; EIP-161 raises it to one, which is what stops a created
  *   account ever again presenting the count an address collision is recognized
  *   by.
  *
  *   ==A number rather than a flag, following the one client that parameterizes
  *   it==
  *
  *   The proposal's own wording is about a starting value, and its parenthetical
  *   is the whole argument for a number: *"increment the nonce over and above
  *   its normal starting value by one (for normal networks, this will be simply
  *   1, however test-nets with non-zero default starting nonces will be
  *   different)"* (`ethereum/EIPs` @ `96523ef4d`, `EIPS/eip-161.md`, Final). A
  *   network whose default is not zero reaches a value neither of a flag's two
  *   states can name.
  *
  *   `besu-eth/besu` @ `c2addd9424` holds exactly that, a
  *   `long initialContractNonce` its fork definitions pass `0` and then `1`,
  *   written unconditionally at `ContractCreationProcessor.java:161`.
  *   `ethereum/go-ethereum-pow` @ `v1.10.26` and `NethermindEth/nethermind` @
  *   `c35ce1b1ab` instead gate a literal on a fork test, which is the shape this
  *   record cannot take: these rules are what a fork resolved TO, so nothing
  *   holding them may ask which fork is active. A flag would then have to carry
  *   the figure in its own name, which is the sentinel problem [[maxCodeSize]]
  *   rejects one paragraph up.
  *
  *   **The write is absolute where the specification's is an increment**, and
  *   the two agree because a creation only reaches an address that [[
  *   Interpreter.deployableAt]] admits, which is an address whose count is
  *   already zero.
  * @param newAccountCharge
  *   when an operation pays the surcharge for bringing its destination into
  *   being. It settles a condition and never a figure: what the two operations
  *   that levy it pay stays [[GasSchedule.newAccount]]'s and
  *   [[GasSchedule.selfDestructNewAccount]]'s, and a network moving one of those
  *   is a repricing that leaves this alone.
  * @param stateAccessMetering
  *   how reaching an account or a storage slot is priced. It settles which
  *   figures the eleven operations that reach state read, never what any of
  *   them costs: both cases spend [[GasSchedule]] fields and neither carries a
  *   number of its own.
  * @param touchSurvivesFailure
  *   the addresses whose reaching by an invocation is NOT undone when that
  *   invocation fails. Everywhere else the rule is that it is:
  *   [[Frame.touchedAccounts]] states how, and the empty set is every network at
  *   every height that has adopted no exception.
  *
  *   ==What the exception is, established rather than inferred from the name==
  *
  *   The proposal's own References note 3 quotes the security alert that
  *   produced it: one client *"was failing to revert empty account deletions
  *   when the transaction causing the deletions of empty accounts ended with an
  *   out-of-gas exception"*, and *"an additional issue was found in Parity,
  *   where the Parity client incorrectly failed to revert empty account
  *   deletions in a more limited set of contexts involving out-of-gas calls to
  *   precompiled contracts; the new Geth behavior matches Parity's"*
  *   (`ethereum/EIPs` @ `96523ef4d`, `EIPS/eip-161.md`, Final). So the amended
  *   revert rule was adopted WITH that exception preserved, deliberately, and
  *   the exception is precompile-shaped.
  *
  *   ==A set of addresses rather than a flag, because the field already
  *   disagrees across two networks==
  *
  *   `besu-eth/besu` @ `c2addd9424` holds the same shape, a set threaded into
  *   both processors from `MainnetProtocolSpecs.java`, and it is the client that
  *   had to serve two networks: `besu-eth/besu-etc` @ `eb4248c997` builds its
  *   Ethereum Classic definitions WITHOUT that set, while
  *   `ethereumclassic/core-geth` @ `4185df450` applies the exception on that
  *   network ungated. A set expresses both readings as data and the empty set as
  *   a network having declined it; a flag naming the incident could express
  *   neither, and a single held address could not express none.
  *
  *   The other four implementations read here narrow the exception to one
  *   hardcoded address -- `ethereum/execution-specs` @ `ccaaaba58`,
  *   `ethereum/go-ethereum-pow` @ `v1.10.26`, `NethermindEth/nethermind` @
  *   `c35ce1b1ab` and `bluealloy/revm` @ `3064c0901c`. **That the narrowing is
  *   historical rather than semantic is an inference and not established**: no
  *   client explains the choice, and nothing here depends on it.
  */
final case class EvmRules(
    table: OpcodeTable,
    schedule: GasSchedule,
    precompiles: PrecompileSet,
    gasForwarded: GasForwarding,
    codeDepositMustSucceed: Boolean,
    maxCodeSize: Option[Int],
    createdAccountNonce: UInt64,
    newAccountCharge: NewAccountCharge,
    storageMetering: StorageMetering,
    stateAccessMetering: StateAccessMetering,
    touchSurvivesFailure: Set[Address]
):

  /** These rules with each proposal applied, in the order given.
    *
    * Order is the caller's to state and is not always free: two proposals
    * touching one price compose to whichever ran last.
    */
  def applying(proposals: Proposal*): EvmRules =
    proposals.foldLeft(this)((held, change) => change(held))
