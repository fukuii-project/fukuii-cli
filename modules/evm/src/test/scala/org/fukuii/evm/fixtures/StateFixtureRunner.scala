package org.fukuii.evm.fixtures

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.crypto.{Keccak256, Secp256k1}
import org.fukuii.evm.*
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.StateTrie
import org.fukuii.types.{Log, Sender, Transaction}

/** Runs one state fixture: seeds the pre-state, settles the transaction around
  * an invocation, and compares the state root it computes against the published
  * one.
  *
  * ==Its own file, because it belongs to no fork==
  *
  * It runs every fork the harness certifies. Its sibling `FrontierTransaction`
  * is correctly named for one, carrying that fork's intrinsic prices and its
  * admission rules -- so the two cannot share a file without the file's name
  * being wrong about one of them.
  */
object StateFixtureRunner:

  /** Runs `fixture` under `rules`.
    *
    * ==No default, deliberately==
    *
    * This took one network's genesis rules when the caller named none, which
    * made the harness quietly opinionated about which chain a corpus belonged
    * to: a caller that forgot to say certified something other than what it
    * meant to, and nothing reported it. The rules a corpus is read under are
    * part of what the corpus IS, so naming them is the caller's job and there
    * is no answer to fall back on.
    */
  def run(fixture: StateFixture, rules: EvmRules): Verdict =
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
    * across it, rather than a judgment made case by case -- and nothing
    * degrades quietly, because wherever bytes are present they settle the
    * question in both directions.
    */
  private def signerOf(transaction: StateTransaction, rules: EvmRules): Signer =
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
      rules: EvmRules,
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
      rules: EvmRules,
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
      rules: EvmRules,
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
            data = transaction.data,
            transfersValue = true
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
            data = Bytes.Empty,
            transfersValue = true
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
