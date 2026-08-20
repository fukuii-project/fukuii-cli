package org.fukuii.evm.fixtures

import org.fukuii.bytes.{Bytes, UInt64}
import org.fukuii.evm.*
import org.fukuii.types.Log

/** Why this fork refuses a transaction.
  *
  * Typed rather than a message, because the corpus states a reason too and a
  * fixture expecting one refusal must not be satisfied by another. Comparing
  * two vocabularies as free text is what made that check unwritable, so these
  * carry the corpus's own names -- coarsened only where one branch here decides
  * what the corpus names in two, which is why there is one [[NonceMismatch]]
  * against its too-low and too-high.
  */
enum Rejection:
  case TypePreFork
  case IntrinsicGasTooLow
  case NonceIsMax
  case GasAllowanceExceeded
  case NonceMismatch
  case InsufficientAccountFunds
  case SenderNotEoa
  case InvalidSignature

/** Whether a transaction may be executed at all, and why not when it may not. */
enum Admission:
  case Admitted(intrinsicGas: BigInt)
  case Rejected(reason: Rejection)

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

  /** What a transaction is charged before any of it runs.
    *
    * `deploys` is the recipient being absent rather than a property of the data,
    * because a transaction that deploys states no recipient -- and the surcharge
    * it pays is priced by the schedule rather than named here, so a fork moving
    * it moves a number.
    */
  def intrinsicCost(schedule: GasSchedule, data: Bytes, deploys: Boolean): BigInt =
    val raw = data.toIArray
    var zeros = 0
    var i = 0
    while i < raw.length do
      if raw(i) == 0.toByte then zeros += 1
      i += 1
    schedule.transactionBase + schedule.transactionDataPerZeroByte * zeros +
      schedule.transactionDataPerNonZeroByte * (raw.length - zeros) +
      (if deploys then schedule.transactionCreate else BigInt(0))

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
    lazy val intrinsic = intrinsicCost(schedule, transaction.data, transaction.to.isEmpty)
    lazy val held = world.balanceOf(transaction.sender).toBigInt
    lazy val nonce = world.nonceOf(transaction.sender).toBigInt
    lazy val maximumFee = transaction.gasLimit * transaction.gasPrice
    if transaction.kind != TransactionKind.Legacy then Admission.Rejected(Rejection.TypePreFork)
    else if intrinsic > transaction.gasLimit then Admission.Rejected(Rejection.IntrinsicGasTooLow)
    else if transaction.nonce >= NonceLimit then Admission.Rejected(Rejection.NonceIsMax)
    else if transaction.gasLimit > block.gasLimit then Admission.Rejected(Rejection.GasAllowanceExceeded)
    else if nonce != transaction.nonce then Admission.Rejected(Rejection.NonceMismatch)
    else if held < maximumFee + transaction.value then Admission.Rejected(Rejection.InsufficientAccountFunds)
    else if world.codeOf(transaction.sender).nonEmpty then Admission.Rejected(Rejection.SenderNotEoa)
    else Admission.Admitted(intrinsic)

/** The nonce a sender holds after the transaction, which admission has already
  * shown to be representable.
  */
private def nextNonce(nonce: BigInt): UInt64 =
  UInt64
    .fromBigInt(nonce + 1)
    .getOrElse(throw new IllegalStateException("an admitted transaction carried an unrepresentable nonce " + nonce))

/** What executing one transaction produced.
  *
  * The refusal and the unsupported operation are separate because they are
  * different kinds of fact: a refusal is this fork's own answer about a
  * transaction, and an unsupported operation is a limit of this build. Sharing
  * one channel let a fixture that expects a refusal absorb an unimplemented
  * opcode silently, which is the direction that matters -- the corpus states an
  * expected refusal exactly where a fork's new rules are under test.
  */
final case class TransactionOutcome(
    logs: Vector[Log],
    rejection: Option[Rejection],
    unsupported: Option[String]
)
