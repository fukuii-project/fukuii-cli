package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.{
  BlockContext,
  Code,
  Environment,
  EvmRules,
  Frame,
  Interpreter,
  JournaledWorldState,
  Message,
  TransactionContext,
  Unsupported,
  Word,
  WorldState
}

/** An invocation a block makes on its own account, before any transaction the
  * block carries has run.
  *
  * ==Named for what the field calls it, and it is not a transaction==
  *
  * All four clients read for this use the word: `besu-eth/besu` @ `b330564a9`
  * (2026-09-09) names a `SystemCallProcessor` and a `SYSTEM_CALL_GAS_LIMIT`,
  * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-09) an `isSystemCall` and a
  * `systemCallGasBudget`, `erigontech/erigon` @ `ab8e9fde7` (2026-09-09) a
  * `SysCallContract` and a `SysCallGasLimit`
  * (`execution/protocol/block_exec.go:53`), and `NethermindEth/nethermind` @
  * `3a98e0818` (2026-09-09) a `SystemCall` type in `Nethermind.Core` beside a
  * `SystemCallBaseGasLimit`.
  *
  * **Two of them also call it a transaction, so the word is not uncontested.**
  * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-08)
  * `src/ethereum/forks/cancun/fork.py:546` is
  * `process_unchecked_system_transaction`, and nethermind's `SystemCall`
  * declaration sits in `Transaction.cs`. That word is not taken here, because
  * everything this project means by a transaction is absent: there is no
  * signature, no sender to recover, no nonce, no charge, no receipt and no
  * entry in any commitment the header states. **A block's gas is not touched
  * either**, so nothing this produces reaches [[BlockOutput.gasUsed]].
  *
  * ==What cannot make one fail, and the one thing that can==
  *
  * *"the call must execute to completion"* and *"the call does not count
  * against the block's gas limit"* (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-09),
  * `EIPS/eip-4788.md`, Final), so a system call has no refusal and cannot
  * invalidate a block -- which is why nothing here returns a
  * [[BlockRejection]]. The one thing it can report is an operation the fork's
  * table admits and this build does not run, which is not a chain result at
  * all: [[org.fukuii.evm.Outcome]] states why that travels as its own type
  * rather than as a halt, and a caller that discarded it would be recording an
  * unbuilt operation as a state this network reached.
  *
  * **That is what keeps this off [[BlockProcessor.process]]'s
  * `irregularStateChange`.** A `WorldState => Unit` has nowhere to put an
  * [[org.fukuii.evm.Unsupported]], and its own contract is a change a schedule
  * knows about for one block -- EIP-779's, *"optional because most blocks have
  * none"* -- where a system call runs on every block from its fork onward.
  *
  * ==The writes are kept whether or not the invocation ended normally==
  *
  * The specification is explicit in the name and in the docstring: *"Process a
  * system transaction without checking if the contract contains code or if the
  * transaction fails"*, and its `incorporate_tx_into_block(system_tx_state)`
  * runs on the statement after `process_message_call` with no branch between
  * them (`fork.py:546-611`). `ethereum/go-ethereum` @ `02872e9ef`
  * `core/state_processor.go:337` discards all three results of its call --
  * `_, _, _ = evm.Call(...)` -- and finalises immediately after. The contrast
  * inside that one file is the sharpest evidence available that the discard is
  * deliberate rather than an oversight: the EIP-2935 call eleven lines below it
  * is written `_, _, err := evm.Call(...)` followed by `if err != nil { panic(err) }`.
  *
  * **What that does NOT mean is that a reverted invocation's own writes
  * survive.** [[org.fukuii.evm.Interpreter.run]] undoes those inside itself, as
  * it does for a transaction, and the specification reaches the same state
  * through the snapshot its `process_message` takes. What is unchecked is the
  * OUTCOME, not the rollback: the caller does not branch on it, and whatever
  * survived the invocation is committed.
  *
  * @param target
  *   the account whose code runs. Not a fork-resolved value and deliberately
  *   not a member of any rule set: the proposal that introduces one names the
  *   address, so it belongs to the proposal rather than to a record a network
  *   could set differently.
  * @param input
  *   what the invocation is called with, which the `CALLDATA` operations read.
  */
final case class SystemCall(target: Address, input: Bytes)

object SystemCall:

  /** The account a system call runs as.
    *
    * `0xfffffffffffffffffffffffffffffffffffffffe`, stated identically by the
    * proposal and by every source read: `ethereum/EIPs` @ `d2a64c2d4`
    * `EIPS/eip-4788.md`'s constants table, `ethereum/execution-specs` @
    * `0cc100eb1` `forks/cancun/fork.py:88`, `ethereum/go-ethereum` @
    * `02872e9ef` `params/protocol_params.go:246`, `besu-eth/besu` @
    * `b330564a9` `SystemCallProcessor.java`'s `SYSTEM_ADDRESS`,
    * `erigontech/erigon` @ `ab8e9fde7` `params.SystemAddress`, and
    * `NethermindEth/nethermind` @ `3a98e0818`'s `Address.SystemUser`.
    *
    * **It is the caller, and on the one contract this build has seen deployed
    * it is the ONLY thing the invocation is distinguished by.** That contract's
    * first three operations are `CALLER`, a push of this address and `EQ`, and
    * the branch decides between writing the block's root and reading an earlier
    * one -- so an invocation made under any other identity silently takes the
    * reading path and stores nothing.
    */
  val Caller: Address =
    Address
      .fromHex("0xfffffffffffffffffffffffffffffffffffffffe")
      .getOrElse(throw new AssertionError("the system caller is a well-formed address"))

  /** What a system call is given to run in, which no fork varies and no block
    * charges for.
    *
    * 30,000,000, stated by the proposal as *"a gas limit of `30_000_000`"*
    * (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-4788.md`, Final) and carried as
    * a constant by every client read: `ethereum/execution-specs` @ `0cc100eb1`
    * `forks/cancun/fork.py:92` `SYSTEM_TRANSACTION_GAS`, `besu-eth/besu` @
    * `b330564a9` `SYSTEM_CALL_GAS_LIMIT`, `NethermindEth/nethermind` @
    * `3a98e0818` `BeaconBlockRootHandler.GasLimit`, and
    * `ethereum/go-ethereum` @ `02872e9ef` `core/state_processor.go:300-309`,
    * which writes the literal twice so that a later fork can widen a separate
    * budget beside it without moving this one.
    *
    * **It bounds the invocation and is charged to nobody.** The block's own
    * limit is not consulted and the figure never reaches
    * [[BlockOutput.gasUsed]], which is what *"the call does not count against
    * the block's gas limit"* means for a layer that produces a header's
    * `gasUsed` from the transactions alone.
    */
  val GasLimit: BigInt = BigInt(30000000)

  /** What the `GASPRICE` operation reports inside a system call.
    *
    * **UNSETTLED. The sources disagree and this build follows the four
    * clients.** `ethereum/execution-specs` @ `0cc100eb1`
    * `forks/cancun/fork.py:578` builds its transaction environment with
    * `gas_price=block_env.base_fee_per_gas`. Every client read states zero
    * instead: `ethereum/go-ethereum` @ `02872e9ef`
    * `core/state_processor.go:326` `GasPrice: uint256.NewInt(0)`,
    * `besu-eth/besu` @ `b330564a9` `SystemCallProcessor.java`'s
    * `.gasPrice(Wei.ZERO)`, `NethermindEth/nethermind` @ `3a98e0818`
    * `BeaconBlockRootHandler.cs`'s `GasPrice = 0`, and `erigontech/erigon` @
    * `ab8e9fde7` `execution/protocol/block_exec.go:228-240`, whose sixth
    * positional argument to `NewMessage` is the gas price and is `&u256.Num0`.
    *
    * **No published case can decide it, and that is measured rather than
    * assumed.** The disagreement is observable only through the `GASPRICE`
    * operation, and the beacon-roots contract does not run one: walking its
    * runtime code and skipping every push immediate leaves `CALLER` and
    * `TIMESTAMP` as the only environment operations it reaches, with `GASPRICE`
    * absent as an instruction and its byte absent from the code entirely. Nor
    * is a contract that would read one reachable through the corpus --
    * `BeaconRootCorpus` records that every published EIP-4788 case carries
    * byte-identical canonical code at that address, or none.
    *
    * **So this is a lead and not a settled value**, per
    * `.claude/rules/evidence-and-citation.md`: the two readings are independent
    * and neither is derived from the other, so the disagreement is the finding.
    * The clients are followed because they are four implementations across
    * three language families against one specification, and because the
    * proposal's own *"the call does not follow the [EIP-1559] burn semantics"*
    * argues against reporting the very charge that mechanism sets.
    *
    * **Reversing trigger: a network deploying a system contract that reads
    * `GASPRICE`.** On such a network the two readings are different state, and
    * the question has to be settled from that network's own specification
    * rather than from this constant.
    *
    * ==The rule that produced the answer, stated because this build has
    * reached the same situation twice and answered it two ways==
    *
    * The other site is `org.fukuii.evm.JournaledWorldState.markAccountCreated`,
    * where the executable specification is again alone -- it keeps a creation
    * marker across a revert and two production clients roll it back -- and
    * where this build followed the SPECIFICATION rather than the clients. Two
    * unobservable divergences resolved opposite ways is a thing a reader will
    * find, so the rule is written down rather than left to be inferred from
    * either answer.
    *
    * **The rule applies only where the divergence is unobservable, and that
    * boundary is not a softening of it.** An observable divergence between this
    * build and a production client is a chain split, so it is a defect to be
    * fixed and never a tie to be broken. What follows governs the case where
    * the sources disagree and no block on any network in scope can tell.
    *
    * **The tiebreak is whether the normative source DECLARES the choice
    * immaterial.**
    *
    *   - **Where it does, there is no disagreement to break.** The clients are
    *     then exercising a latitude the specification granted rather than
    *     contradicting it, both readings conform, and this build follows the
    *     normative source. That is the creation-marker site: the specification
    *     states the marker is kept, states why, and closes with *"this is
    *     harmless"* -- so it has ruled on the divergence itself.
    *   - **Where nothing does, the disagreement is real.** The value is marked
    *     UNSETTLED, implemented from the widest independent evidence available,
    *     and given a trigger that would reverse it. That is this site: the
    *     proposal says nothing, the specification fills a field in a
    *     general-purpose constructor without comment, and four implementations
    *     across three language families fill it otherwise.
    *
    *     **The reading that cuts the other way is recorded rather than left
    *     out.** Five further fields of that same constructor ARE given values
    *     that say "this is not a transaction" -- empty access lists, no blob
    *     hashes, no index in the block, no transaction hash -- so the
    *     specification was willing to think about that constructor's fields,
    *     and filling this one from the block may be a choice rather than an
    *     oversight. It changes nothing here: the tiebreak turns on whether the
    *     divergence was declared immaterial, and no source declares it either
    *     way.
    *
    * **What deliberately does NOT enter the rule is which source the published
    * fixtures reward.** Those are generated from the executable specification,
    * so matching it is what certification rewards while matching the clients is
    * what interoperating with peers rewards -- and the two come apart exactly
    * where a value is unobservable. They come apart with no consequence,
    * because an unobservable divergence is by construction one no fixture can
    * discriminate: a corpus compares roots and hashes, and a value that moves
    * neither is a value it cannot see. Measured at this site rather than
    * assumed, in two parts: the paragraphs above walk the deployed contract's
    * own code and find no `GASPRICE` byte in it, and `BeaconRootCorpus` records
    * that every published EIP-4788 case carries byte-identical canonical code
    * at that address or none -- so no published case could have run a contract
    * that asked.
    *
    * **Its canonical home is `.claude/protocols/consensus-change.md`**,
    * alongside the state-root litmus -- this site's own reasoning is
    * consolidated there as its Instance 2, cited back to this file.
    */
  val GasPrice: BigInt = BigInt(0)

  /** Runs `call` against `world`, keeping what it wrote.
    *
    * ==An address holding no code is answered by running nothing==
    *
    * *"if no code exists at `BEACON_ROOTS_ADDRESS`, the call must fail
    * silently"* (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-4788.md`, Final), and
    * the four implementations reach that silence four different ways while
    * leaving the same state. `besu-eth/besu` @ `b330564a9` tests
    * `maybeContract == null || maybeContract.getCode().isEmpty()` and throws a
    * `SystemCallNoCodeAtAddressException` its Cancun processor catches, so its
    * `systemCallUpdater.commit()` never runs. `ethereum/go-ethereum` @
    * `02872e9ef` `core/vm/evm.go:303-306` returns before creating anything --
    * *"Calling a non-existing account, don't do anything"* -- for any
    * zero-value call under EIP-158. `NethermindEth/nethermind` @ `3a98e0818`
    * answers `(null, null)` from `BeaconRootsAccessList` when
    * `!stateProvider.AccountExists(...)` and executes nothing.
    * `ethereum/execution-specs` @ `0cc100eb1` runs the empty code and succeeds,
    * writing nothing, because `should_transfer_value=False` suppresses its only
    * account-creating step and its `process_message` has carried no
    * `touch_account` since Paris.
    *
    * **The test is taken on the CODE, which is besu's form and agrees with all
    * four on state.** An account absent entirely and an account holding a
    * balance and no code are separate cases, and the four sources split on
    * which they branch for; neither case has anything to run, so every source
    * leaves the state it found.
    *
    * ==Without that test this build would create the account, and nothing
    * downstream would remove it==
    *
    * [[org.fukuii.evm.Interpreter.run]] brings the account it runs as into
    * being before any code runs, and keeps it where the invocation ends
    * normally -- which is correct for a transaction, because
    * [[TransactionProcessor]] offers every account the transaction touched to
    * EIP-161's clearing rule when it settles. **A system call settles no
    * transaction, so nothing offers it to that rule.** An empty account would
    * therefore be committed to the state trie, and a state root that carries
    * one is a chain split against every client above. The deployed case hides
    * this completely: the account is already there, and the difference appears
    * only on a network whose contract was never deployed.
    *
    * **That case is not hypothetical.** `.claude/protocols/consensus-poa.md`
    * makes a network this project authors its own a scheduled stage, and an
    * authored genesis holds no beacon-roots contract unless this client puts
    * one there. The proposal anticipates the same situation from the other
    * direction, warning that omitting the call in favour of writing the storage
    * *"could be problematic on non-mainnet situations in case a different
    * contract is used"*.
    *
    * @param world
    *   the state at the head of the block, which this advances. A journal is
    *   taken over it here rather than by the caller, for the reason
    *   [[BlockProcessor]] takes one per transaction: an invocation that does
    *   not end normally has to leave no trace, and a view with no way to undo
    *   cannot run one.
    * @return
    *   the first operation this build cannot run, where the invocation reached
    *   one, and nothing otherwise. The bytes an invocation returned are
    *   deliberately not reported: no proposal this build has adopted reads one,
    *   and a value nothing consumes would be a member admitted ahead of its
    *   reader.
    */
  def run(
      call: SystemCall,
      world: WorldState,
      block: BlockContext,
      blockHashAt: BigInt => Hash,
      chainId: UInt64,
      rules: EvmRules
  ): Option[Unsupported] =
    val code = world.codeOf(call.target)
    if code.isEmpty then None
    else
      val journal = new JournaledWorldState(world)
      val environment = new Environment(
        journal,
        blockHashAt = blockHashAt,
        block = block,
        transaction = TransactionContext(Caller, GasPrice, Seq.empty),
        chainId = chainId,
        rules = rules
      )
      val frame = new Frame(
        Message(
          caller = Caller,
          currentTarget = call.target,
          codeAddress = Some(call.target),
          value = Word.Zero,
          data = call.input,
          // `should_transfer_value=False` (`forks/cancun/fork.py:600`), and
          // besu and go-ethereum suppress the move rather than passing a flag
          // -- besu by building a frame whose value and apparent value are both
          // zero, go-ethereum by testing `isSystemCall(caller)` before calling
          // `Transfer`. The value is zero at every source, so this is the
          // stated term rather than an observable one.
          transfersValue = false,
          isStatic = false
        ),
        Code(code),
        GasLimit
      )
      val outcome = Interpreter.run(frame, environment)
      // Committed on every outcome, which is what "without checking ... if the
      // transaction fails" asks for. An invocation that reverted or halted has
      // already had its own writes undone inside the interpreter, so what
      // commits here is whatever survived it -- nothing, in that case.
      journal.commit()
      outcome match
        case Left(gap) => Some(gap)
        case Right(_)  => None
