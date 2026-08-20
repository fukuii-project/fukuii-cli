package org.fukuii.evm.fixtures

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.crypto.{Keccak256, Secp256k1}
import org.fukuii.evm.*
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.StateTrie
import org.fukuii.types.{Log, Sender, Transaction}

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

/** Runs one state fixture: seeds the pre-state, settles the transaction around
  * an invocation, and compares the state root it computes against the published
  * one.
  */
object StateFixtureRunner:

  /** The rules a fixture is run under when the caller names none.
    *
    * The baseline, carrying the precompiles the harness prices. A corpus filled
    * for a later fork passes that fork's rules instead, which is the whole of
    * what certifying against one costs here.
    */
  val Baseline: ChainRules = ChainRules.Baseline.copy(precompiles = VmFixtureRunner.precompiles)

  def run(fixture: StateFixture, rules: ChainRules = Baseline): Verdict =
    val trie = VmFixtureRunner.freshTrie()
    val base = new StateTrieWorldState(trie)
    FixtureValues.seed(base, fixture.pre) match
      case Left(error) => Verdict.Skipped(SkipReason.Undecodable(error))
      case Right(())   => executeSeeded(fixture, rules, trie, base)

  /** What reading the published signature established.
    *
    * Three outcomes rather than an address or nothing, because they are three
    * different facts and only one of them is an answer about the transaction.
    */
  private enum Signer:

    /** The sender is settled -- recovered from the published signature, or
      * stated by a corpus that publishes none.
      */
    case Settled(transaction: StateTransaction)

    /** This fork refuses the signature, so there is no sender to run as. */
    case Refused(reason: Rejection)

    /** The published bytes did not decode, so nothing was established. */
    case Unreadable(detail: String)

  /** The account that signed, where the corpus publishes what was signed.
    *
    * ==A stated sender is a convenience; a signature is the authority==
    *
    * No transaction carries a sender -- the specification has no such field and
    * derives one -- so where a fixture publishes the signed bytes, those bytes
    * decide, and a signature this fork refuses refuses the transaction rather
    * than letting it run as whichever account the file happens to name.
    *
    * **The legacy corpus publishes no signed bytes for any of its cases**, so
    * its stated sender stands. That is a property of that corpus, uniform
    * across it, rather than a judgement made case by case -- and nothing
    * degrades quietly, because wherever bytes are present they settle the
    * question in both directions.
    */
  private def signerOf(transaction: StateTransaction, rules: ChainRules): Signer =
    // A transaction of a type this fork predates is refused for its TYPE, and
    // admission is where that is said. Its envelope is not the legacy shape, so
    // attempting recovery reports an unreadable file rather than a refused
    // transaction -- turning two checked cases into two skipped ones. A client
    // meeting one on the wire rejects it by type before doing signature work,
    // and the ordering here is the same.
    if transaction.kind != TransactionKind.Legacy then Signer.Settled(transaction)
    else
      transaction.signed match
        case None        => Signer.Settled(transaction)
        case Some(bytes) =>
          RlpCodec.decodeFrom[Transaction](bytes.toIArray) match
            case Left(error)   => Signer.Unreadable("published signature: " + error)
            case Right(signed) =>
              // The upper half of the order is refused here rather than in
              // recovery, because recovery holds no fork rules and says so: a
              // high `s` and its mirror image recover the SAME account under two
              // different transaction hashes, so this is a duplicate the curve
              // cannot suppress and only a fork can refuse. The comparison is
              // strict, so exactly half the order stays valid.
              if rules.signatureSMustBeLow &&
                Sender.signatureOf(signed).exists(_.s > Secp256k1.halfCurveOrder)
              then Signer.Refused(Rejection.InvalidSignature)
              else
                Sender.recover(signed) match
                  case Left(_)        => Signer.Refused(Rejection.InvalidSignature)
                  case Right(address) => Signer.Settled(transaction.copy(sender = address))

  private def executeSeeded(
      fixture: StateFixture,
      rules: ChainRules,
      trie: StateTrie,
      base: StateTrieWorldState
  ): Verdict =
    signerOf(fixture.transaction, rules) match
      case Signer.Unreadable(detail) => Verdict.Skipped(SkipReason.Undecodable(detail))
      case Signer.Refused(reason)    =>
        val journal = new JournaledWorldState(base)
        journal.commit()
        judge(fixture, base, trie, TransactionOutcome(Vector.empty, Some(reason), None))
      case Signer.Settled(transaction) => executeSigned(fixture, transaction, rules, trie, base)

  private def executeSigned(
      fixture: StateFixture,
      transaction: StateTransaction,
      rules: ChainRules,
      trie: StateTrie,
      base: StateTrieWorldState
  ): Verdict =
    val journal = new JournaledWorldState(base)
    val outcome = FrontierTransaction.admit(journal, fixture.block, transaction, rules.schedule) match
      case Admission.Rejected(reason)       => TransactionOutcome(Vector.empty, Some(reason), None)
      case Admission.Admitted(intrinsicGas) => settle(fixture, transaction, rules, trie, journal, intrinsicGas)
    journal.commit()
    judge(fixture, base, trie, outcome)

  private def settle(
      fixture: StateFixture,
      transaction: StateTransaction,
      rules: ChainRules,
      trie: StateTrie,
      journal: JournaledWorldState,
      intrinsicGas: BigInt
  ): TransactionOutcome =
    val sender = transaction.sender
    // Read before the bump. The address a creation deploys to is derived from
    // the nonce the sender held when it signed, and the specification reaches
    // the same value from the other side -- it increments first and computes
    // the address from `nonce - 1`.
    val signedAt = journal.nonceOf(sender)
    journal.setNonce(sender, nextNonce(transaction.nonce))
    journal.setBalance(sender, journal.balanceOf(sender).sub(Word(transaction.gasLimit * transaction.gasPrice)))
    val environment = new Environment(
      journal,
      blockHashAt = VmFixtureRunner.blockHashOf,
      block = fixture.block,
      transaction = TransactionContext(sender, transaction.gasPrice),
      rules = rules
    )
    val available = transaction.gasLimit - intrinsicGas
    val (frame, result) = transaction.to match
      case Some(recipient) =>
        val called = new Frame(
          Message(
            caller = sender,
            currentTarget = recipient,
            codeAddress = Some(recipient),
            value = Word(transaction.value),
            data = transaction.data
          ),
          Code(journal.codeOf(recipient)),
          available
        )
        (called, Interpreter.run(called, environment))
      case None =>
        // A creation carries its init code as CODE and no input, which is the
        // one place the two shapes differ before the machine sees them.
        val target = ContractAddress.of(sender, signedAt)
        val deploying = new Frame(
          Message(
            caller = sender,
            currentTarget = target,
            codeAddress = None,
            value = Word(transaction.value),
            data = Bytes.Empty
          ),
          Code(transaction.data),
          available
        )
        val outcome =
          if Interpreter.deployableAt(journal, target) then Interpreter.deploy(deploying, environment)
          else Right(Outcome.Halted(Halt.AddressCollision))
        (deploying, outcome)
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
    TransactionOutcome(if succeeded then frame.logs else Vector.empty, None, unsupported)

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
    // The fixture's own statement about the transaction, checked in both
    // directions and by reason. Checking only that SOME refusal occurred is
    // satisfied by any of them, and a refused transaction leaves the state root
    // at its pre-state value whichever branch refused it -- so the root, which
    // is the only other check on these cases, cannot tell one reason from
    // another.
    val settlement = (outcome.rejection, expected.rejection) match
      case (Some(actual), Some(wanted)) if wanted.accepted.contains(actual) => None
      case (Some(actual), Some(wanted))                                     =>
        Some("refused as " + actual + ", but the fixture expects " + wanted.describe)
      case (Some(actual), None) => Some("refused as " + actual + ", but the fixture expects execution")
      case (None, Some(wanted)) => Some("executed, but the fixture expects refusal as " + wanted.describe)
      case (None, None)         => None
    val unsupported = outcome.unsupported.map("this build cannot run " + _)
    val accounts = expected.state.toVector.flatMap { wanted =>
      val slots = (address: Address) => fixture.pre.get(address).fold(Set.empty[BigInt])(_.storage.keySet)
      FixtureValues.divergences(base, wanted, slots)
    }
    val all =
      rootDivergence.toVector ++ logDivergence.toVector ++ settlement.toVector ++ unsupported.toVector ++ accounts
    if all.isEmpty then Verdict.Agreed else Verdict.Diverged(all)
