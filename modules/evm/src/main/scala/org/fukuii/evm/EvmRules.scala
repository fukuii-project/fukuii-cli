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

/** What the operation at `0x44` reports about the block it is running in.
  *
  * ==Why this is a rule and not a second table entry==
  *
  * Every other fork change to an operation is expressible in [[OpcodeTable]]: a
  * proposal adds an entry, removes one, or moves its price. This one does none
  * of those. The byte stays `0x44`, the operation keeps zero inputs and one
  * output, and **its price does not move** -- *"The gas cost of the
  * `DIFFICULTY (0x44)` opcode remains unchanged"* (`ethereum/EIPs` @
  * `dbfa6bee8` (2026-08-26), `EIPS/eip-4399.md:46`), which
  * `ethereum/execution-specs` @ `20f7f6271a` (2026-08-26) confirms by declaring
  * the charge at the same tier on the same line of two consecutive fork modules:
  * `forks/gray_glacier/vm/gas.py:135` is `OPCODE_DIFFICULTY: Final[Uint] = BASE`
  * and `forks/paris/vm/gas.py:135` is `OPCODE_PREVRANDAO: Final[Uint] = BASE`.
  * What changes is which value the operation reads, and an [[Operation]] carries
  * an opcode and a cost rather than a behavior.
  *
  * **A second [[Opcode]] case at `0x44` is the other shape and this build cannot
  * hold it.** That enum is documented as vocabulary rather than membership --
  * *"a byte means the same operation everywhere it is defined at all"* -- and
  * `Opcode.fromCode` is a map keyed on the byte, so two cases sharing one would
  * silently drop whichever the enum happened to order last. `OpcodeSpec` already
  * asserts the bytes are distinct, for that stated reason.
  *
  * **go-ethereum reaches the same arrangement from the other side, and it is the
  * corroboration rather than the model.** `ethereum/go-ethereum` @ `e9e35a42f`
  * (2026-08-26) declares `DIFFICULTY`, `RANDOM` and `PREVRANDAO` in
  * `core/vm/opcodes.go:99-101` as three names for one constant `0x44` -- so its
  * vocabulary has one entry too -- and swaps the behavior in the jump table,
  * `core/vm/jump_table.go:147` putting `opRandom` at that byte over the fork
  * below it. Its two implementations read different members of one block
  * context: `core/vm/instructions.go:452` reads `Context.Difficulty` and `:457`
  * reads `Context.Random`.
  *
  * ==Renaming the vocabulary entry is declined, and the document only asks==
  *
  * EIP-4399 puts the rename at `SHOULD` rather than `MUST`, twice and
  * separately: *"The `mixHash` field **SHOULD** further be renamed to
  * `prevRandao`"* and *"The `DIFFICULTY (0x44)` opcode **SHOULD** further be
  * renamed to `PREVRANDAO (0x44)`"* (`EIPS/eip-4399.md:50` and `:52`).
  *
  * **Renaming [[Opcode.Difficulty]] would be false of the other network family
  * this project serves.** A proof-of-work network runs that operation reporting
  * a difficulty for its whole life, so a shared vocabulary naming the byte after
  * the reading only one family ever adopts states that family's answer under no
  * network's name -- which is what `.claude/rules/nomenclature.md` forbids of a
  * name read at the shared level. The document's own name is carried by
  * [[BlockRandomness.Eip4399]] instead, where it describes the reading rather
  * than the byte.
  */
enum BlockRandomness:

  /** The network runs no randomness beacon, and the operation reports the
    * block's own difficulty.
    *
    * The answer at every fork below the first that supplies one, and the
    * permanent answer on a network that never does.
    */
  case Unavailable

  /** EIP-4399: the operation reports the randomness the beacon chain settled
    * for the previous block.
    *
    * ==It is read as bytes and not as a number==
    *
    * The value is a 32-byte field widened to a machine word, where a difficulty
    * is a quantity that was already one. `ethereum/execution-specs` @
    * `20f7f6271a` `forks/paris/vm/instructions/block.py:195` pushes
    * `U256.from_be_bytes(evm.message.block_env.prev_randao)` where
    * `forks/gray_glacier/.../block.py` pushes `U256(...block_env.difficulty)`,
    * and `besu-eth/besu` @ `fdf1247c6d` pushes
    * `frame.getBlockValues().getMixHashOrPrevRandao()` -- a big-endian reading
    * of the same 32 bytes in both.
    */
  case Eip4399

/** Which accounts a destruction may remove.
  *
  * ==Data rather than a boolean, for the reason [[NewAccountCharge]] is==
  *
  * The two readings do not differ by a threshold. One removes whatever the
  * operation ran as; the other asks a question of the transaction first, and
  * that question is answered by a record no other rule reads. A flag would
  * leave the difference at the site that destroys, where the negation reads as
  * an accident rather than as a fork's answer.
  *
  * ==What varies is the REMOVAL, and the burn follows it rather than sitting
  * beside it==
  *
  * The value an operation sweeps to its beneficiary moves under both cases; the
  * two differ in whether the account is then emptied and taken away. That
  * matters exactly once -- where the beneficiary IS the account ending -- and
  * the case below carries it, because the sweep is then a no-op and the
  * emptying is the whole of the effect. All three sources put the two acts
  * under one condition rather than under two.
  *
  * ==A network that empties without removing adds a case, and the field already
  * shows one coming==
  *
  * Whether a removal empties the account is a SECOND axis, and both production
  * clients model it as a second flag rather than as a third state of this one:
  * `ethereum/go-ethereum` @ `02872e9ef` gates the emptying on
  * `!evm.chainRules.IsAmsterdam` inside its already-branched destruction
  * (`core/vm/instructions.go:946-952`), and `besu-eth/besu` @ `b330564a9` reads
  * `gasCalculator().isSelfDestructBalancePreserved()` beside its own
  * `willBeDestroyed` (`evm/.../operation/SelfDestructOperation.java:124,146-151`).
  * **So the axes are separate in the field and are kept separate here**; this
  * enum is not the place to put one.
  */
enum SelfDestructScope:

  /** Whatever the operation ran as.
    *
    * The original rule and every fork below EIP-6780: an account that destroys
    * itself is emptied and taken away, whatever created it and whenever. Where
    * it names itself as its beneficiary the sweep moves nothing and the
    * emptying stands, so the value is destroyed -- which the proposal that
    * narrows this describes as the behavior it is leaving behind: *"Previously
    * it was possible to burn ether by calling `SELFDESTRUCT` targeting the
    * executing contract as the beneficiary"* (`ethereum/EIPs` @ `d2a64c2d4`,
    * `EIPS/eip-6780.md`, Final).
    */
  case AnyAccount

  /** Only an account a creation this same transaction ran brought into being.
    *
    * EIP-6780: *"`SELFDESTRUCT` does not delete any data (including storage
    * keys, code, or the account itself)"* where the account predates the
    * transaction, and *"continues to behave as it did prior to this EIP"* where
    * it does not.
    *
    * ==The burn splits with it, and the two halves of the document say so
    * separately==
    *
    * For an account that predates the transaction, *"if the target is the same
    * as the contract calling `SELFDESTRUCT` there is no net change in balances.
    * Unlike the prior specification, Ether will not be burnt in this case"*.
    * For one created in it, *"if the target is the same as the contract calling
    * `SELFDESTRUCT` that Ether will be burnt"*. The document's own backwards
    * compatibility section states the pair as one sentence: *"If the contract
    * existed prior to the transaction the ether will not be burned. If the
    * contract was newly created in the transaction the ether will be burned, as
    * before"* (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-6780.md`, Final).
    *
    * ==What counts as created is the CREATION STARTING, not the code landing==
    *
    * *"A contract is considered created at the beginning of a create
    * transaction or when a CREATE series operation begins execution"*, and *"if
    * a balance exists at the contract's new address it is still considered to
    * be a contract creation"* (same document). So the record is written where a
    * deployment begins rather than where it deposits code, which is what makes
    * an account that destroys itself from its own initialization code a member.
    *
    * `org.fukuii.evm.JournaledWorldState.wasCreatedInTransaction` is what
    * answers this, and its own documentation carries why that record survives a
    * failed invocation where every other member of that type does not.
    */
  case AccountsCreatedInTransaction

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
  * @param maxInitcodeSize
  *   the longest code that may be handed to a creation to initialize it, where
  *   the network bounds it at all. A creation given more than this fails; one
  *   given exactly this succeeds, the proposal's comparison being strictly
  *   greater. `None` is a network that bounds nothing, which is every height
  *   before EIP-3860.
  *
  *   ==It is DERIVED from [[maxCodeSize]] and stored anyway, and the field
  *   splits on which==
  *
  *   EIP-3860 defines it as `2 * MAX_CODE_SIZE` and requires EIP-170, so it is
  *   not an independent quantity. Four implementations compute it rather than
  *   holding it: `ethereum/execution-specs` @ `20f7f6271a` declares
  *   `MAX_INIT_CODE_SIZE = 2 * MAX_CODE_SIZE` beside the bound it doubles
  *   (`forks/shanghai/vm/interpreter.py:62`), `NethermindEth/nethermind` @
  *   `b92e2a4719` makes it an extension property, `MaxInitCodeSize => 2 *
  *   spec.MaxCodeSize` (`IReleaseSpecExtensions.cs:15`), and
  *   `ethereum/go-ethereum` @ `e9e35a42f` writes `MaxInitCodeSize = 2 *
  *   MaxCodeSize` (`params/protocol_params.go:161`), and
  *   `ethereumclassic/core-geth` @ `4185df450` writes the same line at
  *   `params/vars/protocol_params.go:61`. **`besu-eth/besu` @ `fdf1247c6d`
  *   stores the pair instead**, carrying a code-size and an initcode-size limit
  *   side by side at each entry of `EvmSpecVersion`.
  *
  *   **The doubling is not a fixed constant either, and go-ethereum is where
  *   that shows**: the same file declares a second pair one line below for a
  *   later fork, so the derived bound moves when the bound it derives from
  *   does. A network is therefore not entitled to assume 49,152.
  *
  *   Stored here rather than computed at each read, because the two questions
  *   *is a bound in force* and *what is it* are separate and only a stored
  *   option answers the first. A computed `maxCodeSize.map(_ * 2)` would put a
  *   bound on initcode at every height that bounds deployed code, which is every
  *   height from EIP-170 -- three forks before the document that bounds
  *   initcode. **The derivation still happens once, where the document that
  *   defines it is adopted**, so there is no second figure to keep in step.
  * @param coinbaseStartsWarm
  *   whether the account a block pays its fees to is reached at the reduced
  *   price from the first invocation of every transaction. It settles a
  *   membership in the set seeded before the outermost invocation and never a
  *   figure: what a reach costs stays [[GasSchedule.warmAccess]]'s and
  *   [[GasSchedule.coldAccountAccess]]'s.
  *
  *   **It is read only where [[stateAccessMetering]] is [[StateAccessMetering.WarmCold]]**,
  *   because below that scheme there is no set to be in. EIP-3651's frontmatter
  *   is `requires: 2929` for that reason, and a network holding this true under
  *   [[StateAccessMetering.Settled]] changes nothing -- which is a property of
  *   the seed rather than a guard written anywhere.
  *
  *   ==A member here rather than a third case of [[StateAccessMetering]]==
  *
  *   That type settles *how a reach is priced*, and this changes no price: both
  *   readings charge the same figures for the same reaches, and differ only in
  *   what is already in the set when the transaction starts. A third case would
  *   put a membership question inside the type that every pricing site matches
  *   on, where the two new cases would be indistinguishable at all eleven of
  *   them.
  *
  *   **The field splits on representation and agrees on the separation.**
  *   `besu-eth/besu` @ `fdf1247c6d` carries `boolean warmCoinbase` on the
  *   processor its fork constructs, set false at three of its definitions and
  *   true at the later ones (`MainnetTransactionProcessor.java:81`,
  *   `MainnetProtocolSpecs.java`) -- the same shape [[codeDepositMustSucceed]]
  *   is modeled on, from the same client.
  *   `NethermindEth/nethermind` @ `b92e2a4719` holds `IsEip3651Enabled` and
  *   exposes it under the rule's own name,
  *   `AddCoinbaseToTxAccessList => spec.IsEip3651Enabled`.
  *   `bluealloy/revm` @ `08c17c162` takes the third shape, an
  *   `Option<Address>` on its warm-address set, where absence is the network not
  *   having adopted the document. **None of the three makes it a case of the
  *   metering scheme.**
  *
  *   `ethereum/go-ethereum` @ `e9e35a42f` is the one that branches on a fork name
  *   instead -- `if rules.IsShanghai { al.AddAddress(coinbase) }`
  *   (`core/state/statedb.go:1518`) -- which is the shape these rules cannot
  *   take, being what a fork resolved TO.
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
  * @param reservedCodePrefix
  *   a leading byte no deployment may store, absent where no proposal reserves
  *   one. Carried as the byte rather than as a flag for the reason
  *   [[maxCodeSize]] carries its bound rather than a flag: both are constraints
  *   on what a deployment may store, and a flag would hide the constant in the
  *   check that reads it.
  *
  *   Held as an `Int` rather than a `Byte` because the reserved value is 0xEF
  *   and a `Byte` in this language is signed -- the literal does not fit, and a
  *   member typed to need `.toByte` at every comparison invites a sign error at
  *   the one site that forgets it.
  *
  *   The field agrees on the rule and splits on how to say it:
  *   `ethereum/go-ethereum-pow` @ `v1.10.26` tests `ret[0] == 0xEF` behind a
  *   fork flag, `besu-eth/besu` @ `fdf1247c6d` installs a `PrefixCodeRule` as a
  *   contract-validation rule, and `ethereum/execution-specs` @ `20f7f6271a`
  *   compares inline in the fork module that has it. None parameterizes the
  *   byte, so holding it as data is this build's choice rather than the field's
  *   -- taken because the alternative states the same fact twice, once as a
  *   boolean here and once as a literal in the machine.
  * @param blockRandomness
  *   what the operation at `0x44` reports about the block it runs in.
  *   [[BlockRandomness]] carries the evidence for the pair, and for why this is
  *   a member here rather than a second entry in [[table]].
  * @param selfDestructScope
  *   which accounts a destruction may remove. [[SelfDestructScope]] carries the
  *   evidence, including why the value a destruction burns splits with the
  *   removal rather than being a rule of its own.
  *
  *   ==A rule here rather than a second entry in [[table]], where one surveyed
  *   client puts it==
  *
  *   `ethereum/go-ethereum` @ `02872e9ef` registers a DIFFERENT operation for
  *   the byte -- `opSelfdestruct` below the fork and `opSelfdestruct6780` at and
  *   above it (`core/vm/instructions.go:904,930`), selected by its fork-resolved
  *   jump table. That shape is not available here: an [[Operation]] carries a
  *   price and no behavior, so the table has nowhere to put the difference.
  *   `besu-eth/besu` @ `b330564a9` takes the shape this member does, a value on
  *   the operation its fork constructs
  *   (`evm/.../operation/SelfDestructOperation.java:124`).
  */
final case class EvmRules(
    table: OpcodeTable,
    schedule: GasSchedule,
    precompiles: PrecompileSet,
    gasForwarded: GasForwarding,
    codeDepositMustSucceed: Boolean,
    maxCodeSize: Option[Int],
    maxInitcodeSize: Option[Int],
    coinbaseStartsWarm: Boolean,
    createdAccountNonce: UInt64,
    newAccountCharge: NewAccountCharge,
    storageMetering: StorageMetering,
    stateAccessMetering: StateAccessMetering,
    touchSurvivesFailure: Set[Address],
    reservedCodePrefix: Option[Int],
    blockRandomness: BlockRandomness,
    selfDestructScope: SelfDestructScope
):

  /** These rules with each proposal applied, in the order given.
    *
    * Order is the caller's to state and is not always free: two proposals
    * touching one price compose to whichever ran last.
    */
  def applying(proposals: Proposal*): EvmRules =
    proposals.foldLeft(this)((held, change) => change(held))
