package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.*
import org.fukuii.types.{AccessTuple, Log}

/** One transaction, as the values settling it spends.
  *
  * ==Not [[org.fukuii.types.Transaction]], and the difference is the sender==
  *
  * That type is the signed envelope a peer sends and a block carries. This is
  * what is left once the signature has been read: the sender it recovered to,
  * and the fields settlement acts on. `ethereum/go-ethereum` @ `6bb0588ad`
  * draws the same line, building a separate flattened record for its state
  * transition rather than passing the signed transaction down; the name it uses
  * for that record is already this project's word for one invocation, so it is
  * not reused here.
  *
  * ==The quantities are arbitrary precision, and that is what the corpus
  * requires==
  *
  * A published state test states a nonce, a limit, a price and a value that a
  * fixed-width type cannot always hold -- overflow at each of them is a thing
  * the corpus tests. Narrowing here would turn a case the fork must refuse into
  * a case this build cannot read, which is a silent loss of coverage rather
  * than a refusal.
  *
  * ==The name states a precondition nothing here enforces==
  *
  * Settlement takes what admission accepted. That the nonce matches, that the
  * sender can fund the fee and the value, and that the limit covers the
  * intrinsic charge are all decided before this exists, and none of them is
  * re-checked below -- so building one of these around a transaction admission
  * would have refused is a caller error, and the name is what says whose.
  * [[org.fukuii.evm.Environment.blockHashAt]] carries its contract the same way
  * and for the same reason.
  *
  * @param sender
  *   the account the signature recovered to, which pays the fee and whose
  *   transaction count moves.
  * @param to
  *   the recipient, absent when the transaction deploys. Absence is what makes
  *   it a deployment -- there is no separate flag, here or in the
  *   specification, whose own transaction type declares
  *   `to: Bytes0 | Address` (`ethereum/execution-specs` @ `ccaaaba58`,
  *   `forks/frontier/transactions.py`).
  * @param intrinsicGas
  *   what the transaction is charged before any of it runs, as admission
  *   computed it.
  *
  *   **It is carried rather than recomputed, because it is one number.** The
  *   figure admission compared against the limit is the figure settlement
  *   spends, and a second computation is a second definition of it --
  *   [[IntrinsicGas]] states the same rule from the side that owns the number.
  *
  *   **That [[accessList]] now sits beside it does not make recomputing safe.**
  *   The fields a fork prices the charge from are the fork's to choose, and this
  *   record is not obliged to grow with them; it carries the declaration because
  *   settlement has its own use for it, not so that the charge could be worked
  *   out again here.
  * @param accessList
  *   the accounts and slots the transaction declared ahead of running, empty for
  *   every format that carries no such declaration.
  *
  *   **Settlement needs it for a second reason, which is why it is here and not
  *   only on the record admission built.** What it seeds is the set of things
  *   the machine may then reach at the reduced price, and that seeding happens
  *   at the outermost invocation -- the one place a caller could not supply it
  *   from anywhere else.
  */
final case class AdmittedTransaction(
    sender: Address,
    nonce: BigInt,
    gasPrice: BigInt,
    baseFeePerGas: BigInt,
    gasLimit: BigInt,
    to: Option[Address],
    value: BigInt,
    data: Bytes,
    accessList: Seq[AccessTuple],
    intrinsicGas: BigInt
)

/** What settling one transaction produced.
  *
  * @param gasUsed
  *   what the transaction is charged for, after the refund it earned. This is
  *   the figure a receipt accumulates, not the figure the fee was computed from
  *   -- the specification names them `total_gas_used` and `gas_used` separately
  *   for that reason.
  * @param logs
  *   what the transaction emitted, oldest first, and empty where it failed. A
  *   successful transaction that emitted none is also empty, so this does not
  *   report which happened.
  * @param succeeded
  *   whether the invocation ended normally. It is stated rather than inferred
  *   from [[logs]] because a transaction can succeed and emit nothing, and it
  *   is what a receipt's status field is built from once a fork carries one.
  * @param unbuilt
  *   the operation this build cannot run, where one was reached.
  *
  *   **A settlement carrying this is not a chain result**, and the field is
  *   separate from every other one so that no caller can read it as one:
  *   [[org.fukuii.evm.Outcome]] keeps the same fact out of its own enumeration
  *   for the same reason. What the other fields hold is the settlement an
  *   exceptional halt would have produced, which is the only shape in which the
  *   world ends somewhere a caller can compare -- a run that stopped partway
  *   would leave the fee unsettled and look like a divergence in arithmetic
  *   nobody performed.
  */
final case class Settlement(
    gasUsed: BigInt,
    logs: Vector[Log],
    succeeded: Boolean,
    unbuilt: Option[Unsupported]
)

/** What settling a transaction does around an invocation.
  *
  * ==Rules are a fork's; a processor is a network's==
  *
  * [[ExecutionRules]] records why: of the two clients read here only
  * `besu-eth/besu` @ `c2addd9424` makes a transaction processor a member of a
  * fork-resolved specification, and its own definitions set one seven times
  * against twenty-three for the gas calculator, while
  * `NethermindEth/nethermind` @ `c35ce1b1ab` carries a separate processor per
  * network it serves and selects between them when a node is assembled.
  * This is one such processor, and what varies by fork reaches it as values.
  *
  * ==Everything here sits outside the machine, deliberately==
  *
  * The intrinsic charge, the upfront purchase of gas, the refund cap, the fee
  * and the destruction of registered accounts are all things the specification
  * does in `process_transaction` and not in the interpreter. Two of them look
  * like they belong inside and do not: a refund is counted by the operation
  * that earns it and settled against the transaction, and a `SELFDESTRUCT`
  * only registers -- `ethereum/execution-specs` @ `ccaaaba58` destroys at
  * `frontier/fork.py` after the message call returns, so a registered account
  * goes on answering reads and goes on running when called for the rest of the
  * transaction, and a registration inside an invocation that later fails is
  * discarded with it.
  */
object TransactionProcessor:

  /** Settles `transaction` against `world`, running it under `rules`.
    *
    * ==The order is the specification's, and two steps of it are load-bearing==
    *
    * The transaction count moves and the whole fee is taken before the machine
    * runs, so an invocation reading its sender's balance sees what it would see
    * on a chain, and an invocation that halts has still paid. What comes back
    * afterwards is the unused gas plus whatever refund the transaction earned,
    * bounded by a fraction of what it spent -- a fraction a proposal moves, so
    * the divisor is read from [[ExecutionRules]] rather than written here.
    *
    * The nonce is read out of `world` before it is bumped rather than taken
    * from the transaction, because the address a deployment lands at is derived
    * from it. Admission has already established the two are equal, so this is
    * which of two equal values is authoritative rather than a second check.
    *
    * ==`world` is committed here, and destruction happens after that==
    *
    * A settlement is the point at which held writes become state, so this
    * commits rather than leaving a caller to. Destruction then runs against
    * what is underneath, which is not a choice: a journal has no way to undo a
    * destruction, and none is needed, because nothing after this point can fail.
    *
    * @param destroyAccount
    *   removes an account and the storage under it. Called once per account a
    *   successful transaction registered, after every one of that transaction's
    *   writes has landed.
    *
    *   It arrives as a parameter for the reason
    *   [[org.fukuii.evm.Environment.blockHashAt]] does: it is a thing this layer
    *   calls rather than a value it reads, and what satisfies it -- a trie, a
    *   test double, a view at an earlier block -- varies underneath.
    *   [[org.fukuii.evm.WorldState]] deliberately does not carry it, being the
    *   whole of what the *machine* may ask, and no operation destroys anything.
    *
    *   **Removing the leaf is not enough.** The account's storage must go with
    *   it, or the next write to that address commits to storage that was
    *   supposed to be gone. `org.fukuii.trie.StateTrie.destroyAccount` is what
    *   satisfies this here, and it does so by evicting its memo of that
    *   account's storage trie -- which is correct only where the builder it was
    *   given returns an empty trie, a requirement stated on that parameter and
    *   not on this one.
    */
  def settle(
      transaction: AdmittedTransaction,
      world: JournaledWorldState,
      destroyAccount: Address => Unit,
      block: BlockContext,
      blockHashAt: BigInt => Hash,
      chainId: UInt64,
      rules: EvmRules,
      execution: ExecutionRules
  ): Settlement =
    val sender = transaction.sender
    val signedAt = world.nonceOf(sender)
    world.setNonce(sender, nextNonce(transaction.nonce))
    moveBalance(world, sender, -(transaction.gasLimit * transaction.gasPrice))
    val environment = new Environment(
      world,
      blockHashAt = blockHashAt,
      block = block,
      transaction = TransactionContext(sender, transaction.gasPrice),
      chainId = chainId,
      rules = rules
    )
    val available = transaction.gasLimit - transaction.intrinsicGas
    val (frame, outcome) = invoke(transaction, world, environment, signedAt, available)
    account(transaction, world, destroyAccount, block, frame, outcome, rules, execution)

  /** Runs the transaction's one outermost invocation, whichever shape it has.
    *
    * A deployment carries its init code as CODE and no input, which is the one
    * place the two shapes differ before the machine sees them, and it refuses an
    * address already in use rather than deploying over it --
    * [[org.fukuii.evm.Interpreter.deployableAt]] holds the survey and the
    * operator decision behind that.
    *
    * ==Both shapes may change state, and this is the only layer that can say
    * so==
    *
    * *"This flag is set to `false` initially"* (`ethereum/EIPs` @ `9e393a79`,
    * `EIPS/eip-214.md`, Final), and initially means here: nothing above an
    * outermost invocation asks for one, and inside the machine only an
    * operation can set it. So both constructions below answer `false`, and no
    * transaction of any type this build admits answers otherwise.
    */
  private def invoke(
      transaction: AdmittedTransaction,
      world: JournaledWorldState,
      environment: Environment,
      signedAt: UInt64,
      available: BigInt
  ): (Frame, Either[Unsupported, Outcome]) =
    transaction.to match
      case Some(recipient) =>
        val called = new Frame(
          Message(
            caller = transaction.sender,
            currentTarget = recipient,
            codeAddress = Some(recipient),
            value = Word(transaction.value),
            data = transaction.data,
            transfersValue = true,
            isStatic = false
          ),
          Code(world.codeOf(recipient)),
          available,
          reachedBeforeEntry = warmAtEntry(transaction, environment, recipient),
          slotsReachedBeforeEntry = warmSlotsAtEntry(transaction, environment)
        )
        (called, Interpreter.run(called, environment))
      case None =>
        val target = ContractAddress.of(transaction.sender, signedAt)
        val deploying = new Frame(
          Message(
            caller = transaction.sender,
            currentTarget = target,
            codeAddress = None,
            value = Word(transaction.value),
            data = Bytes.Empty,
            transfersValue = true,
            isStatic = false
          ),
          Code(transaction.data),
          available,
          reachedBeforeEntry = warmAtEntry(transaction, environment, target),
          slotsReachedBeforeEntry = warmSlotsAtEntry(transaction, environment)
        )
        val ran =
          if Interpreter.deployableAt(world, target) then Interpreter.deploy(deploying, environment)
          else Right(Outcome.Halted(Halt.AddressCollision))
        (deploying, ran)

  /** What the outermost invocation may reach at the reduced price before it has
    * reached anything.
    *
    * ==Four members, and the fifth a reader will look for is NOT here==
    *
    * The sender, the account being called or the address being deployed at,
    * every precompile, and whatever the transaction declared. *"`accessed_addresses`
    * is initialized to include the `tx.sender`, `tx.to` (or the address being
    * created if it is a contract creation transaction) and the set of all
    * precompiles"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2929.md`, Final),
    * with EIP-2930's declaration added by its own document.
    *
    * **THE BLOCK'S BENEFICIARY IS NOT WARM HERE**, and putting it in is a
    * consensus divergence on every block whose transactions reach that account
    * -- a `BALANCE` or a `CALL` to it would be charged the reduced figure a fork
    * early. It becomes warm at a later proposal, EIP-3651, and
    * `ethereum/go-ethereum` @ `e9e35a42f` shows how easily the two are
    * conflated: its `StateDB.Prepare` takes the beneficiary in its signature and
    * warms it under `if rules.IsShanghai`, with the Berlin members listed
    * unguarded above it.
    *
    * ==A set, where the same declaration is counted as a sequence for the
    * charge==
    *
    * `ethereum/execution-specs` @ `20f7f6271` opens
    * `forks/berlin/utils/message.py` with `accessed_addresses = set()` and adds
    * into it, while the intrinsic charge beside it loops the sequence. Reaching
    * for one shape at both sites is wrong in one direction or the other.
    */
  private def warmAtEntry(
      transaction: AdmittedTransaction,
      environment: Environment,
      target: Address
  ): Set[Address] =
    environment.rules.stateAccessMetering match
      case StateAccessMetering.Settled  => Set.empty
      case StateAccessMetering.WarmCold =>
        environment.rules.precompiles.addresses +
          transaction.sender +
          target ++
          transaction.accessList.map(_.address)

  /** The slots the same declaration names, keyed by the account each belongs to.
    *
    * Empty but for the declaration: nothing else about a transaction names a
    * slot, so this has no counterpart to the three members [[warmAtEntry]] adds
    * beside the declaration.
    */
  private def warmSlotsAtEntry(
      transaction: AdmittedTransaction,
      environment: Environment
  ): Set[(Address, Word)] =
    environment.rules.stateAccessMetering match
      case StateAccessMetering.Settled  => Set.empty
      case StateAccessMetering.WarmCold =>
        transaction.accessList.flatMap { entry =>
          entry.storageKeys.map(key => (entry.address, Word.fromBytes(Bytes.fromIArray(key.toBytes))))
        }.toSet

  /** Settles what the invocation left: the refund, the fee, and the accounts it
    * registered.
    *
    * ==What a failed invocation earned is nothing, and that is three things==
    *
    * Its logs go with it, and so do its refunds and its registrations. The
    * specification reaches the same place by emptying all three on the value
    * its message call returns; here the frame still holds them, so each is read
    * only where the invocation succeeded. An account registered by an
    * invocation that then failed must not be destroyed, which is the one of the
    * three whose omission would be a state root difference rather than a
    * receipt difference.
    *
    * ==Its gas is a fourth thing and does NOT follow that rule==
    *
    * A halt keeps nothing; a revert keeps whatever it had not spent. So what
    * returns to the sender is read from which failure it was rather than from
    * the fact that it failed, and the specification reports it that way too:
    * `gas_left=evm.gas_left` sits on every path, beside the three that are
    * emptied on the error alone (`ethereum/execution-specs` @ `20f7f6271a`,
    * `src/ethereum/forks/byzantium/vm/interpreter.py:123-140`).
    *
    * ==The refund is capped against what was spent, not against the limit==
    *
    * Half of the gas the transaction actually consumed. A cap against the limit
    * would let a transaction that spent little claim a refund it never earned
    * the room for.
    *
    * ==Clearing runs last, and the proposal says so in as many words==
    *
    * *"At the end of the transaction is immediately following the execution of
    * the suicide list, prior to the determination of the state trie root for
    * receipt population"* (`ethereum/EIPs` @ `96523ef4d`, `EIPS/eip-161.md`,
    * Final). So it goes after the registrations above and before
    * [[BlockProcessor]] takes the root a receipt carries -- the proposal fixes
    * the placement, so no count of implementations is doing any work here. Reads still answer after the commit, because a journal
    * with nothing held passes them through.
    */
  private def account(
      transaction: AdmittedTransaction,
      world: JournaledWorldState,
      destroyAccount: Address => Unit,
      block: BlockContext,
      frame: Frame,
      outcome: Either[Unsupported, Outcome],
      rules: EvmRules,
      execution: ExecutionRules
  ): Settlement =
    val (gasLeft, succeeded, unbuilt) = outcome match
      case Left(gap)                             => (BigInt(0), false, Some(gap))
      case Right(Outcome.Stopped(remaining, _))  => (remaining, true, None)
      case Right(Outcome.Reverted(remaining, _)) => (remaining, false, None)
      case Right(Outcome.Halted(_))              => (BigInt(0), false, None)
    val spent = transaction.gasLimit - gasLeft
    val earned = if succeeded then frame.refundCounter else BigInt(0)
    val refunded = (spent / execution.maxRefundQuotient).min(earned)
    val used = spent - refunded
    val returned = transaction.gasLimit - used
    moveBalance(world, transaction.sender, returned * transaction.gasPrice)
    // The producer is credited the TIP alone. What the block charged is not
    // moved anywhere -- not to the producer, not to a treasury, not to an
    // account at all -- so the burn is an omission rather than a transfer, and
    // there is no address to look for. `ethereum/execution-specs` @
    // `20f7f6271a` `forks/london/fork.py:820-821` credits exactly this
    // difference and never mentions the rest.
    //
    // One expression covers both eras because the charge is zero below any fee
    // market, which is the same collapse the specification notes at `:819`.
    moveBalance(world, block.coinbase, used * (transaction.gasPrice - transaction.baseFeePerGas))
    world.commit()
    if succeeded then frame.accountsToDelete.foreach(destroyAccount)
    if execution.touchedEmptyAccountsAreDeleted then
      // The existence check is load-bearing, not defensive: `deadAt` answers
      // true for an absent account by design, so the pair is what the
      // specification spells `account_exists_and_is_empty` -- and it is what
      // narrows the wider set `Interpreter.incorporateAfterFailure` builds back
      // to the one that specification would have destroyed.
      touchedAccounts(frame, succeeded, block, rules).foreach: address =>
        if world.accountExists(address) && Interpreter.deadAt(world, address) then destroyAccount(address)
    Settlement(used, if succeeded then frame.logs else Vector.empty, succeeded, unbuilt)

  /** Every account this transaction reached, as the clearing rule is offered
    * them.
    *
    * ==Offered unfiltered, and whether each is empty is asked at the one site
    * that consumes this==
    *
    * [[org.fukuii.evm.Frame.touchedAccounts]] states why the question is asked
    * once rather than at each recording site.
    *
    * ==The beneficiary is always here, and it is never the invocation's==
    *
    * The fee is paid whether or not the invocation succeeded, so the account it
    * reaches is reached on both paths -- while everything the invocation reached
    * is discarded on one of them. The proposal counts it: an account changes
    * state when *"as the block author ('miner') it is the recipient of
    * block-rewards or transaction-fees of zero or more value"*, and its Notes
    * name *"a zero-gas-price fees transfer"* as one of the four contexts that
    * can leave an account holding nothing (`ethereum/EIPs` @ `96523ef4d`,
    * `EIPS/eip-161.md`, Final). `ethereum/execution-specs` @ `ccaaaba58` keeps
    * it out of the machine's set for exactly that reason and settles it in a
    * branch of its own.
    *
    * **The beneficiary is credited unconditionally above and removed again
    * here**, where that specification declines to credit a zero and removes only
    * an account that was already there. The two leave the same state, and
    * `besu-eth/besu` @ `c2addd9424` and `ethereum/go-ethereum-pow` @ `v1.10.26`
    * both take the route this does.
    *
    * ==THE SWITCH: whether the exception survives the OUTERMOST failure is one
    * expression, and it is the `else` below==
    *
    * A nested invocation that failed has already met the machine's own half of
    * the rule, which keeps [[org.fukuii.evm.EvmRules.touchSurvivesFailure]] and
    * drops everything else. The outermost invocation has no caller to do that
    * for it, and the authorities differ:
    *
    *   - `ethereum/execution-specs` @ `ccaaaba58` wipes the whole set with no
    *     exception. `forks/spurious_dragon/vm/interpreter.py`'s
    *     `process_message_call` assigns an empty set on any error, and the
    *     exception lives only in `vm/__init__.py`'s
    *     `incorporate_child_on_error`, which an outermost invocation never
    *     reaches.
    *   - `ethereum/go-ethereum-pow` @ `v1.10.26` keeps it, through a second
    *     increment that no entry backs. `stateObject.touch` journals a
    *     `touchChange` like any other change, and `journal.revert` decrements
    *     the dirty-set count for every entry it unwinds -- so the reach on its
    *     own does not survive. For the exempt address `touch` then calls
    *     `journal.dirty` a second time, raising that count with no entry beside
    *     it (`core/state/state_object.go`, `core/state/journal.go`), and that is
    *     the increment no revert can reach, so `StateDB.Finalise` still iterates
    *     the address. Its own comment there records the same: the address
    *     *"will persist in the journal even though the journal is reverted"*.
    *     `besu-eth/besu` @ `c2addd9424` keeps it too:
    *     `AbstractMessageProcessor`'s force-delete runs from the frame-failure
    *     path, which the initial frame reaches like any other.
    *
    * **The two are separated by EXECUTION and not only by a reading of their
    * sources.** Byte-identical signed transactions were run against both, over
    * one pre-state holding the exempt address existing and empty beside a
    * funded sender, sent straight to that address with empty calldata -- so gas
    * is the only variable between the cases:
    *
    *   - **One gas short of its base charge the outermost invocation fails, and
    *     the two post-state roots DIFFER.** `ethereum/go-ethereum-pow` @
    *     `v1.10.26` destroys the account; `ethereum/execution-specs` @
    *     `ccaaaba58` leaves it standing.
    *   - **Given exactly that charge the invocation succeeds, and the two roots
    *     are identical.** That agreement is the calibration: pre-state, harness
    *     and gas arithmetic all agree, so the failing frame is the only thing
    *     left that can explain the row above.
    *   - **A third case sends the transaction elsewhere and both keep the
    *     account**, which is what separates destroyed-by-the-reach from never
    *     having been in the pre-state at all.
    *
    * **Sharper than "the two apply different deletion rules": at that frame the
    * clause is not taking effect at all.** The same body run under Frontier
    * rules -- a fork with no state-clearing rule to apply -- produces the
    * specification's own root for this fork byte for byte. The specification
    * lands on exactly the state the clause had not yet arrived to change.
    *
    * **No mainnet state root decides it, because the chain never reached this
    * frame.** The proposal states its own activation at 2,675,000 and the
    * address is existing-and-empty for 119 blocks; at 2,675,119 it is DELETED,
    * by the transaction `ethereum/legacytests` @ `1f581b8cc` publishes as
    * `GeneralStateTests/stSpecialTest/failed_tx_xcf416c53.json` -- which reached
    * it through a NESTED call, where the two agree. **What closed the window is
    * the account ceasing to EXIST, not ceasing to be empty**, and an absent
    * account cannot be touched-and-empty: the same distinction the existence
    * check at the call site above turns on. It stays absent until 2,687,391,
    * and over the whole span from activation to there exactly one transaction
    * is sent directly to any precompile -- the one in 2,687,391 that recreated
    * the account, outside the window, and it SUCCEEDED.
    *
    * **Neither published corpus decides it either, and the measurement is
    * narrower than "no case names that address".** The generated tier for this
    * fork carries 537 cases across 34 files, and the address is in none of
    * them. The legacy tier holds exactly one case sending a transaction there
    * -- and that case publishes no post section for this fork and holds no
    * account at the address in its pre-state, so it neither runs under these
    * rules nor presents the existing-and-empty account the decision turns on.
    * **That tier could not decide it in principle**, being generated from the
    * specification, so it holds no case whose expected value contradicts the
    * specification.
    *
    * **So this keeps it on a JUDGMENT rather than on a measurement**: two
    * production implementations of independent lineage agree, the specification
    * stands alone, and nothing on the chain or in either corpus separates them.
    * The other reading is `else Set.empty` on the line below and nothing else.
    *
    * **The authority above is the two oracles, which are external to this
    * project and share no code.** The runs and the block-by-block scan were
    * performed by `fukuii-project/fukuii-tests` @ `239d481c5`, whose
    * `networks/ethereum/mainnet/state/accounts/ripemd160_touch_exemption.json`
    * carries the vector, both roots for the failing frame, and the scan --
    * the instrument that ran the oracles, never the source of truth.
    */
  private def touchedAccounts(
      frame: Frame,
      succeeded: Boolean,
      block: BlockContext,
      rules: EvmRules
  ): Set[Address] =
    val reachedByTheMachine =
      if succeeded then frame.touchedAccounts
      else frame.touchedAccounts & rules.touchSurvivesFailure
    reachedByTheMachine + block.coinbase

  /** `account`'s balance moved by `delta`, refusing a result no account can
    * hold.
    *
    * ==The word wraps by contract, so every fee move needs this==
    *
    * [[org.fukuii.evm.Word]] is the machine's word and is modular in both
    * directions: a debit below zero answers a near-2^256 CREDIT, and a credit
    * past the ceiling answers a small balance. Either is a fee no network ever
    * charged, arriving as a plausible figure with nothing to distinguish it
    * from one the chain agreed on -- so the arithmetic is done in arbitrary
    * precision and the result is bounded before the word is built from it.
    *
    * Admission establishes that the sender holds the whole fee it offers plus
    * the value it sends, and what comes back afterwards is bounded by what was
    * taken, so neither end is a state a chain reaches. A caller that settled a
    * transaction admission would have refused is what makes it one, which is a
    * broken precondition and is raised as one. [[nextNonce]] and
    * [[BlockProcessor]]'s cumulative gas carry their contracts the same way and
    * for the same reason.
    */
  private def moveBalance(world: JournaledWorldState, account: Address, delta: BigInt): Unit =
    val moved = world.balanceOf(account).toBigInt + delta
    if moved < 0 || moved > Word.MaxValue.toBigInt then
      throw new IllegalStateException(
        "a settled transaction moved " + account.toString + " to a balance no account can hold: " + moved.toString
      )
    world.setBalance(account, Word(moved))

  /** The transaction count the sender holds afterwards.
    *
    * Admission refuses a nonce at the ceiling, so the successor is
    * representable by the time this is reached and a caller that settled an
    * unadmitted transaction is what makes it not -- which is a broken
    * precondition rather than a state a chain can reach, and is raised as one.
    */
  private def nextNonce(nonce: BigInt): UInt64 =
    UInt64
      .fromBigInt(nonce + 1)
      .getOrElse(
        throw new IllegalStateException("a settled transaction carried an unrepresentable nonce " + nonce.toString)
      )
