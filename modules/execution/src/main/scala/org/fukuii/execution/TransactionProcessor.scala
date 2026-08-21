package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.*
import org.fukuii.types.Log

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
  *   it a deployment -- there is no separate flag, in this type or in any
  *   surveyed client.
  */
final case class AdmittedTransaction(
    sender: Address,
    nonce: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    to: Option[Address],
    value: BigInt,
    data: Bytes
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
  * [[ExecutionRules]] records why: no surveyed client makes a transaction
  * processor a member of a fork-resolved specification except besu, whose own
  * definitions set one seven times against twenty-three for the gas calculator,
  * while `NethermindEth/nethermind` @ `c35ce1b1ab` carries a separate processor
  * per network it serves and selects between them when a node is assembled.
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
    * capped at half of what it spent.
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
      rules: EvmRules
  ): Settlement =
    val sender = transaction.sender
    val signedAt = world.nonceOf(sender)
    world.setNonce(sender, nextNonce(transaction.nonce))
    world.setBalance(sender, world.balanceOf(sender).sub(Word(transaction.gasLimit * transaction.gasPrice)))
    val environment = new Environment(
      world,
      blockHashAt = blockHashAt,
      block = block,
      transaction = TransactionContext(sender, transaction.gasPrice),
      rules = rules
    )
    val available = transaction.gasLimit - IntrinsicGas.of(rules.schedule, transaction.data, transaction.to.isEmpty)
    val (frame, outcome) = invoke(transaction, world, environment, signedAt, available)
    account(transaction, world, destroyAccount, block, frame, outcome)

  /** Runs the transaction's one outermost invocation, whichever shape it has.
    *
    * A deployment carries its init code as CODE and no input, which is the one
    * place the two shapes differ before the machine sees them, and it refuses an
    * address already in use rather than deploying over it --
    * [[org.fukuii.evm.Interpreter.deployableAt]] holds the survey and the
    * operator decision behind that.
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
            transfersValue = true
          ),
          Code(world.codeOf(recipient)),
          available
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
            transfersValue = true
          ),
          Code(transaction.data),
          available
        )
        val ran =
          if Interpreter.deployableAt(world, target) then Interpreter.deploy(deploying, environment)
          else Right(Outcome.Halted(Halt.AddressCollision))
        (deploying, ran)

  /** Settles what the invocation left: the refund, the fee, and the accounts it
    * registered.
    *
    * ==What a failed invocation earned is nothing, and that has three parts==
    *
    * Its gas is gone, its logs go with it, and so do its refunds and its
    * registrations. The specification reaches the same place by resetting all
    * four on the value its message call returns; here the frame still holds
    * them, so each is read only where the invocation succeeded. An account
    * registered by an invocation that then halted must not be destroyed, which
    * is the one of the four whose omission would be a state root difference
    * rather than a receipt difference.
    *
    * ==The refund is capped against what was spent, not against the limit==
    *
    * Half of the gas the transaction actually consumed. A cap against the limit
    * would let a transaction that spent little claim a refund it never earned
    * the room for.
    */
  private def account(
      transaction: AdmittedTransaction,
      world: JournaledWorldState,
      destroyAccount: Address => Unit,
      block: BlockContext,
      frame: Frame,
      outcome: Either[Unsupported, Outcome]
  ): Settlement =
    val (gasLeft, succeeded, unbuilt) = outcome match
      case Left(gap)                            => (BigInt(0), false, Some(gap))
      case Right(Outcome.Stopped(remaining, _)) => (remaining, true, None)
      case Right(Outcome.Halted(_))             => (BigInt(0), false, None)
    val spent = transaction.gasLimit - gasLeft
    val earned = if succeeded then frame.refundCounter else BigInt(0)
    val refunded = (spent / 2).min(earned)
    val used = spent - refunded
    val returned = transaction.gasLimit - used
    world.setBalance(transaction.sender, world.balanceOf(transaction.sender).add(Word(returned * transaction.gasPrice)))
    world.setBalance(block.coinbase, world.balanceOf(block.coinbase).add(Word(used * transaction.gasPrice)))
    world.commit()
    if succeeded then frame.accountsToDelete.foreach(destroyAccount)
    Settlement(used, if succeeded then frame.logs else Vector.empty, succeeded, unbuilt)

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
