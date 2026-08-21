package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import org.fukuii.bytes.Address
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.crypto.{Keccak256, Secp256k1}
import org.fukuii.evm.{JournaledWorldState, StateTrieWorldState}
import org.fukuii.execution.{AdmittedTransaction, Settlement, TransactionProcessor}
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.StateTrie
import org.fukuii.types.{Log, Sender, Transaction}

/** The same published state corpora, settled by the layer that settles a
  * transaction in production rather than by the harness's own driver.
  *
  * ==Why a second path over one corpus, and what it is worth==
  *
  * `CertificationCorpora` reads these files and settles them with a driver that
  * lives in test scope, which its own documentation is candid about: a result
  * produced through it is evidence about the machine AND about that driver
  * together. What runs here is `org.fukuii.execution.TransactionProcessor`,
  * which is production code, over the identical files at the identical rules.
  * The two paths therefore disagree exactly where the driver and the real layer
  * disagree, and the counts asserted beside this are what stop the second path
  * quietly covering less material than the first.
  *
  * **The two collapse into one when the driver goes.** This exists because
  * reconciling them is a separate piece of work from building the layer, not
  * because one corpus deserves two runners.
  *
  * ==Admission is still the driver's, and that is the boundary here==
  *
  * What decides whether a transaction may run at all has not been built yet, so
  * every case below is admitted by the same code the other path admits it with.
  * What differs between the two paths is settlement and nothing else, which is
  * what makes a divergence here attributable.
  */
object SettlementCorpora:

  /** Every report, or nothing at all when the harness cannot be assembled, on
    * the same terms `CertificationCorpora.reports` states.
    *
    * The interpreter tier is absent: it states an invocation directly, with no
    * transaction around it, so there is nothing here for it to exercise.
    */
  lazy val reports: Option[Vector[CorpusReport]] =
    CertificationCorpora.stateCorpora.map(_.map(report))

  private def report(corpus: CertificationCorpora.StateCorpus): CorpusReport =
    val CertificationCorpora.StateCorpus(name, directory, fork, rules) = corpus
    val files = FixtureCorpus.jsonFilesUnder(directory)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(StateFixture.decodeFile(file.getFileName.toString, _, fork)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(contents) =>
          val skipped = contents.withoutExpectation.map { case_ =>
            CaseOutcome(case_, Verdict.Skipped(SkipReason.NoExpectationAtThisFork))
          }
          val ran = contents.fixtures.map { fixture =>
            CertificationCorpora.outcomeOf(fixture.name)(run(fixture, rules))
          }
          skipped ++ ran
    }
    CorpusReport(name, files.length, outcomes)

  /** Runs one fixture, settling whatever admission accepts through the real
    * layer.
    */
  private def run(fixture: StateFixture, rules: UpgradeRules): Verdict =
    val trie = VmFixtureRunner.freshTrie()
    val base = new StateTrieWorldState(trie)
    FixtureValues.seed(base, fixture.pre) match
      case Left(error) => Verdict.Skipped(SkipReason.Undecodable(error))
      case Right(())   =>
        signerOf(fixture.transaction, rules.admission.signatureSMustBeLow) match
          case Signer.Unreadable(detail)   => Verdict.Skipped(SkipReason.Undecodable(detail))
          case Signer.Refused(reason)      => judge(fixture, base, trie, Left(reason))
          case Signer.Settled(transaction) =>
            val journal = new JournaledWorldState(base)
            FrontierTransaction.admit(journal, fixture.block, transaction, rules.evm.schedule) match
              case Admission.Rejected(reason) => judge(fixture, base, trie, Left(reason))
              case Admission.Admitted(_)      =>
                val settlement = TransactionProcessor.settle(
                  admitted(transaction),
                  journal,
                  trie.destroyAccount,
                  fixture.block,
                  VmFixtureRunner.blockHashOf,
                  rules.evm
                )
                judge(fixture, base, trie, Right(settlement))

  /** The fixture's transaction as the values settlement spends.
    *
    * Every quantity crosses unchanged and unnarrowed. A corpus states a nonce, a
    * limit, a price and a value that no fixed-width type always holds, because
    * overflow at each of them is a thing it tests.
    */
  private def admitted(transaction: StateTransaction): AdmittedTransaction =
    AdmittedTransaction(
      sender = transaction.sender,
      nonce = transaction.nonce,
      gasPrice = transaction.gasPrice,
      gasLimit = transaction.gasLimit,
      to = transaction.to,
      value = transaction.value,
      data = transaction.data
    )

  /** What reading the published signature established.
    *
    * The same three outcomes the other path draws, for the same reason: a
    * signature this fork refuses refuses the transaction rather than letting it
    * run as whichever account the file happens to name, and a published
    * signature that does not decode establishes nothing at all.
    */
  private enum Signer:
    case Settled(transaction: StateTransaction)
    case Refused(reason: Rejection)
    case Unreadable(detail: String)

  private def signerOf(transaction: StateTransaction, signatureSMustBeLow: Boolean): Signer =
    if transaction.kind != TransactionKind.Legacy then Signer.Settled(transaction)
    else
      transaction.signed match
        case None        => Signer.Settled(transaction)
        case Some(bytes) =>
          RlpCodec.decodeFrom[Transaction](bytes.toIArray) match
            case Left(error)   => Signer.Unreadable("published signature: " + error)
            case Right(signed) =>
              if signatureSMustBeLow && Sender.signatureOf(signed).exists(_.s > Secp256k1.halfCurveOrder) then
                Signer.Refused(Rejection.InvalidSignature)
              else
                Sender.recover(signed) match
                  case Left(_)        => Signer.Refused(Rejection.InvalidSignature)
                  case Right(address) => Signer.Settled(transaction.copy(sender = address))

  /** Every way the state this reached disagrees with the state the fixture
    * publishes.
    *
    * A refused transaction leaves the pre-state exactly as it was, so the root
    * cannot tell one refusal from another and the reason is compared on its own.
    */
  private def judge(
      fixture: StateFixture,
      base: StateTrieWorldState,
      trie: StateTrie,
      outcome: Either[Rejection, Settlement]
  ): Verdict =
    val expected = fixture.expectation
    val root = trie.stateRoot
    val emitted = Keccak256.hash(RlpCodec.encodeTo[Seq[Log]](outcome.fold(_ => Vector.empty[Log], _.logs)))
    val rootDivergence = Option.when(root != expected.root)("state root " + root.toHex + " != " + expected.root.toHex)
    val logDivergence = expected.logs.flatMap { want =>
      Option.when(emitted != want)("logs " + emitted.toHex + " != " + want.toHex)
    }
    val settlement = (outcome.left.toOption, expected.rejection) match
      case (Some(actual), Some(wanted)) if wanted.accepted.contains(actual) => None
      case (Some(actual), Some(wanted))                                     =>
        Some("refused as " + actual + ", but the fixture expects " + wanted.describe)
      case (Some(actual), None) => Some("refused as " + actual + ", but the fixture expects execution")
      case (None, Some(wanted)) => Some("executed, but the fixture expects refusal as " + wanted.describe)
      case (None, None)         => None
    val unbuilt = outcome.toOption.flatMap(_.unbuilt).map("this build cannot run " + _.opcode.toString)
    val accounts = expected.state.toVector.flatMap { wanted =>
      val slots = (address: Address) => fixture.pre.get(address).fold(Set.empty[BigInt])(_.storage.keySet)
      FixtureValues.divergences(base, wanted, slots)
    }
    val all =
      rootDivergence.toVector ++ logDivergence.toVector ++ settlement.toVector ++ unbuilt.toVector ++ accounts
    if all.isEmpty then Verdict.Agreed else Verdict.Diverged(all)
