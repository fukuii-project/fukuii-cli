package org.fukuii.evm.fixtures

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.evm.*
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.StateTrie
import org.fukuii.types.Log

/** Whether a transaction may be executed at all, and why not when it may not. */
enum Admission:
  case Admitted(intrinsicGas: BigInt)
  case Rejected(reason: String)

/** Everything a state fixture needs that sits ABOVE the machine: the intrinsic
  * charge, the upfront purchase of gas, the refund, the fee, and the removal of
  * accounts an invocation registered.
  *
  * ==This is the harness standing in for a layer that does not exist yet==
  *
  * A published state fixture states a transaction, and a transaction is settled
  * one layer above the interpreter -- which is where account removal belongs
  * too, and why the machine only registers. Nothing in this repository settles
  * one yet, so the fixture harness assembles it here, in test scope, to reach
  * the corpus at all.
  *
  * **A result produced through this is evidence about the machine AND about
  * this driver together.** Where a fixture disagrees, which of the two is wrong
  * is a question to answer rather than an answer to assume; the inline
  * expectations beside the published root are what make that answerable.
  *
  * Every rule below is the executable specification's `frontier` fork, which is
  * the same source the generated half of the corpus was filled from.
  */
object FrontierTransaction:

  /** The intrinsic prices now live in [[GasSchedule]] rather than here.
    *
    * They were three `val`s in this file, which made the fork seam complete for
    * opcodes and precompiles and absent for the charge every transaction pays
    * first -- and EIP-2028 is precisely a repricing-in-place of the non-zero-byte
    * price, so the one delta kind the seam most needs to express was the one it
    * could not. Read them from the schedule the caller supplies.
    */

  /** A nonce at or above this cannot be signed for, applied to every fork. */
  val NonceLimit: BigInt = (BigInt(1) << 64) - 1

  def intrinsicCost(schedule: GasSchedule, data: Bytes): BigInt =
    val raw = data.toIArray
    var zeros = 0
    var i = 0
    while i < raw.length do
      if raw(i) == 0.toByte then zeros += 1
      i += 1
    schedule.transactionBase + schedule.transactionDataPerZeroByte * zeros +
      schedule.transactionDataPerNonZeroByte * (raw.length - zeros)

  /** Whether the block would carry this transaction, checked in the order the
    * specification checks it.
    */
  def admit(
      world: WorldState,
      block: BlockContext,
      transaction: StateTransaction,
      schedule: GasSchedule
  ): Admission =
    // LAZY, and the laziness is the point rather than a micro-optimisation.
    // These four were strict, so every one of them ran before the first branch --
    // including for a transaction rejected immediately for being a type this fork
    // predates. `maximumFee` is an unbounded multiplication of two magnitudes the
    // caller supplies, performed four lines above the branch that bounds one of
    // them. Admission is exactly where that ordering matters, because admission is
    // what faces a transaction that arrived from somewhere else. Scala's `else if`
    // already short-circuits, so deferring each to its own use is a reordering and
    // not a behavior change; `intrinsic` is read twice and a `lazy val` computes it
    // once.
    lazy val intrinsic = intrinsicCost(schedule, transaction.data)
    lazy val held = world.balanceOf(transaction.sender).toBigInt
    lazy val nonce = world.nonceOf(transaction.sender).toBigInt
    lazy val maximumFee = transaction.gasLimit * transaction.gasPrice
    if transaction.kind != TransactionKind.Legacy then Admission.Rejected("a transaction type this fork predates")
    else if intrinsic > transaction.gasLimit then Admission.Rejected("gas below the intrinsic cost")
    else if transaction.nonce >= NonceLimit then Admission.Rejected("nonce at the limit")
    else if transaction.gasLimit > block.gasLimit then Admission.Rejected("gas above what the block has left")
    else if nonce != transaction.nonce then Admission.Rejected("nonce mismatch")
    else if held < maximumFee + transaction.value then Admission.Rejected("balance below the maximum fee plus value")
    else if world.codeOf(transaction.sender).nonEmpty then Admission.Rejected("sender carries code")
    else Admission.Admitted(intrinsic)

/** The nonce a sender holds after the transaction, which admission has already
  * shown to be representable.
  */
private def nextNonce(nonce: BigInt): UInt64 =
  UInt64
    .fromBigInt(nonce + 1)
    .getOrElse(throw new IllegalStateException("an admitted transaction carried an unrepresentable nonce " + nonce))

/** What executing one transaction produced. */
final case class TransactionOutcome(logs: Vector[Log], failure: Option[String])

/** Runs one state fixture: seeds the pre-state, settles the transaction around
  * an invocation, and compares the state root it computes against the published
  * one.
  */
object StateFixtureRunner:

  def run(fixture: StateFixture): Verdict =
    fixture.transaction.to match
      case None            => Verdict.Skipped(SkipReason.TransactionLevelCreation)
      case Some(recipient) => execute(fixture, recipient)

  private def execute(fixture: StateFixture, recipient: Address): Verdict =
    val trie = VmFixtureRunner.freshTrie()
    val base = new StateTrieWorldState(trie)
    FixtureValues.seed(base, fixture.pre) match
      case Left(error) => Verdict.Skipped(SkipReason.Undecodable(error))
      case Right(())   => executeSeeded(fixture, recipient, trie, base)

  private def executeSeeded(
      fixture: StateFixture,
      recipient: Address,
      trie: StateTrie,
      base: StateTrieWorldState
  ): Verdict =
    val journal = new JournaledWorldState(base)
    val transaction = fixture.transaction
    val outcome = FrontierTransaction.admit(journal, fixture.block, transaction, GasSchedule.Baseline) match
      case Admission.Rejected(reason)       => TransactionOutcome(Vector.empty, Some(reason))
      case Admission.Admitted(intrinsicGas) => settle(fixture, recipient, trie, journal, intrinsicGas)
    journal.commit()
    judge(fixture, base, trie, outcome)

  private def settle(
      fixture: StateFixture,
      recipient: Address,
      trie: StateTrie,
      journal: JournaledWorldState,
      intrinsicGas: BigInt
  ): TransactionOutcome =
    val transaction = fixture.transaction
    val sender = transaction.sender
    journal.setNonce(sender, nextNonce(transaction.nonce))
    journal.setBalance(sender, journal.balanceOf(sender).sub(Word(transaction.gasLimit * transaction.gasPrice)))
    val frame = new Frame(
      Message(
        caller = sender,
        currentTarget = recipient,
        codeAddress = Some(recipient),
        value = Word(transaction.value),
        data = transaction.data
      ),
      Code(journal.codeOf(recipient)),
      transaction.gasLimit - intrinsicGas
    )
    val environment = new Environment(
      journal,
      blockHashAt = VmFixtureRunner.blockHashOf,
      block = fixture.block,
      transaction = TransactionContext(sender, transaction.gasPrice)
    )
    val result = Interpreter.run(
      frame,
      OpcodeTable.baseline(GasSchedule.Baseline),
      GasSchedule.Baseline,
      VmFixtureRunner.precompiles,
      environment
    )
    val (gasLeft, succeeded, unsupported) = result match
      case Left(gap)                            => (BigInt(0), false, Some(gap.opcode.toString))
      case Right(Outcome.Stopped(remaining, _)) => (remaining, true, None)
      case Right(Outcome.Halted(_))             => (BigInt(0), false, None)
    val usedBeforeRefund = transaction.gasLimit - gasLeft
    val earned = if succeeded then frame.refundCounter else BigInt(0)
    val refunded = (usedBeforeRefund / 2).min(earned)
    val used = usedBeforeRefund - refunded
    val returned = transaction.gasLimit - used
    journal.setBalance(sender, journal.balanceOf(sender).add(Word(returned * transaction.gasPrice)))
    journal.touch(fixture.block.coinbase)
    journal.setBalance(
      fixture.block.coinbase,
      journal.balanceOf(fixture.block.coinbase).add(Word(used * transaction.gasPrice))
    )
    if succeeded then
      journal.commit()
      frame.accountsToDelete.foreach(trie.destroyAccount)
    TransactionOutcome(if succeeded then frame.logs else Vector.empty, unsupported.map("this build cannot run " + _))

  private def judge(
      fixture: StateFixture,
      base: StateTrieWorldState,
      trie: StateTrie,
      outcome: TransactionOutcome
  ): Verdict =
    val expected = fixture.expectation
    val root = trie.stateRoot
    val rootDivergence = Option.when(root != expected.root)("state root " + root.toHex + " != " + expected.root.toHex)
    val emitted = Keccak256.hash(RlpCodec.encodeTo[Seq[Log]](outcome.logs))
    val logDivergence = expected.logs.flatMap { want =>
      Option.when(emitted != want)("logs " + emitted.toHex + " != " + want.toHex)
    }
    val rejection = (outcome.failure, expected.rejected) match
      case (Some(reason), false) if reason.startsWith("this build") => Some(reason)
      case _                                                        => None
    val accounts = expected.state.toVector.flatMap { wanted =>
      val slots = (address: Address) => fixture.pre.get(address).fold(Set.empty[BigInt])(_.storage.keySet)
      FixtureValues.divergences(base, wanted, slots)
    }
    val all = rootDivergence.toVector ++ logDivergence.toVector ++ rejection.toVector ++ accounts
    if all.isEmpty then Verdict.Agreed else Verdict.Diverged(all)
