package org.fukuii.consensus.pos.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path

import io.circe.Json

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.consensus.pos.*
import org.fukuii.types.{Bloom, Withdrawal}

/** The published engine tier, run once and reported as counts.
  *
  * ==What this certifies, and it is one claim==
  *
  * That the header this build derives from a published payload hashes to the
  * block hash the payload states. A block hash is a digest over every header
  * field, so reproducing one requires the three constants the merge fixed, both
  * derived commitments, the seal's two slots, the tail's depth and every field's
  * position to be right together — and nothing about it can be partly true.
  *
  * ==Why these two labels and no others==
  *
  * Their fixtures configure `{network, chainid}` and nothing else. Every later
  * label adds `blobSchedule`, which is a Cancun dependency this build's fork
  * ladder does not reach — measured across all 7,261 cases of these two, and
  * calibrated against `for_cancun`, where the key is present.
  *
  * They are also the built range: this project's Ethereum schedule reaches
  * Shanghai and stops, so a later label would be certifying a fork nothing here
  * activates.
  *
  * ==What it CANNOT certify, which matters as much as what it can==
  *
  * Both labels predate blob transactions, the parent beacon block root and
  * execution requests. So the blob-gas link of the header's tail, the
  * beacon-root link, the requests-hash refusal and every fork-gate window above
  * Shanghai are untouched here however many cases agree. Those are exercised by
  * hand-written cases in this module and by nothing published.
  *
  * It also certifies nothing about EXECUTION. A payload's `stateRoot`,
  * `receiptsRoot` and `logsBloom` are copied into the header and re-hashed
  * without being recomputed, so a case whose state transition this build would
  * get wrong still agrees here. That is the boundary of the layer rather than a
  * weakness of the corpus.
  */
object EnginePayloadCorpus:

  /** The two labels, named as the release names them. */
  val Labels: Vector[String] = Vector("for_paris", "for_shanghai")

  private def directory(root: Path, label: String): Path =
    FixtureCorpus.generated(root).resolve("blockchain_tests_engine").resolve(label)

  /** One report per label, or nothing at all when the corpus cannot be located.
    *
    * `None` rather than empty reports, for the reason the corpora above it give:
    * a harness answering with empty reports is indistinguishable from one that
    * found nothing wrong. The spec beside this turns `None` into a loud failure
    * rather than a cancellation, because a canceled case is counted by nothing
    * and a run whose corpus vanished would otherwise report the same total as
    * one that read it.
    */
  lazy val reports: Option[Vector[CorpusReport]] = runs.map(_.map(_._1))

  /** What each label actually exercises, beside what it agrees with.
    *
    * ==A count of agreements says nothing about what was reached==
    *
    * Every payload here derives a header, so agreement is broad by
    * construction. Which COMMITMENTS that touches is a different question, and
    * the answer is uneven: a withdrawals root over a real list is reached by a
    * few dozen payloads out of thousands, while the same field's empty-trie
    * value is reached by nearly all of them.
    *
    * Counting it is what stops the unevenness from being invisible. A tier
    * reporting only agreement would read identically if the corpus lost every
    * payload that carried a withdrawal.
    */
  lazy val coverage: Option[Vector[(String, Coverage)]] =
    runs.map(_.map((report, cover) => report.corpus -> cover))

  private lazy val runs: Option[Vector[(CorpusReport, Coverage)]] =
    FixtureCorpus.root.map(root => Labels.map(label => assemble(root, label)))

  private def assemble(root: Path, label: String): (CorpusReport, Coverage) =
    val files = FixtureCorpus.jsonFilesUnder(directory(root, label))
    val decoded = files.flatMap { file =>
      FixtureCorpus.read(file) match
        case Left(reason) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(reason))) -> None)
        case Right(text) => outcomesOf(file.getFileName.toString, text)
    }
    val cover = decoded.foldLeft(Coverage.Empty)((acc, entry) => entry._2.fold(acc)(acc.observing))
    (CorpusReport(label, files.length, decoded.map(_._1)), cover)

  private def outcomesOf(fileName: String, text: String): Vector[(CaseOutcome, Option[ExecutionPayload])] =
    io.circe.parser.parse(text) match
      case Left(failure) =>
        Vector(CaseOutcome(fileName, Verdict.Skipped(SkipReason.Undecodable(failure.getMessage))) -> None)
      case Right(json) =>
        json.asObject
          .map(_.toVector.flatMap((name, body) => casesOf(name, body)))
          .getOrElse(
            Vector(CaseOutcome(fileName, Verdict.Skipped(SkipReason.Undecodable("top level is not an object"))) -> None)
          )

  private def casesOf(name: String, body: Json): Vector[(CaseOutcome, Option[ExecutionPayload])] =
    body.hcursor.downField("engineNewPayloads").as[Vector[Json]] match
      case Left(_) =>
        Vector(CaseOutcome(name, Verdict.Skipped(SkipReason.Undecodable("no engineNewPayloads array"))) -> None)
      case Right(entries) =>
        entries.zipWithIndex.map((entry, index) => outcomeOf(name + "#" + index.toString, entry))

  /** What running one published payload established.
    *
    * ==Three groups, and only two of them are assertions about this layer==
    *
    * A payload the corpus expects to be accepted must derive a header that
    * hashes to what it states. A payload the corpus expects to be rejected FOR
    * ITS BLOCK HASH must be refused, which is the only published exercise of
    * the refusal path this tier has.
    *
    * **Everything else the corpus expects to be rejected is skipped and
    * counted.** Those name a transaction-level defect or a block-level gas rule
    * — neither of which this layer reaches — and the header over such a block
    * is ordinarily well formed, so asserting agreement would be asserting
    * something the corpus is not saying and asserting refusal would be wrong.
    * The count under that reason is the size of the work that would close it.
    */
  private[certification] def outcomeOf(name: String, entry: Json): (CaseOutcome, Option[ExecutionPayload]) =
    val expected = entry.hcursor.downField("validationError").as[String].toOption
    decodeRequest(entry) match
      case Left(reason)   => CaseOutcome(name, Verdict.Skipped(SkipReason.Undecodable(reason))) -> None
      case Right(request) =>
        // Coverage is observed over the payloads the tier DECIDED. A skipped one
        // was decoded and translated too, but nothing here asserts its outcome,
        // so counting it would report reach the assertions do not have.
        val decided = Some(request.payload)
        val derived = PayloadTranslation.checkedHeaderOf(request)
        expected match
          case Some(error) if error.contains("INVALID_BLOCK_HASH") =>
            val verdict = derived match
              case Left(TranslationRefusal.BlockHashMismatch(_, _)) => Verdict.Agreed
              case Left(other)                                      =>
                Verdict.Diverged(Vector("expected a block-hash refusal, refused " + other.toString))
              case Right(_) =>
                Verdict.Diverged(Vector("expected a block-hash refusal, derived a matching header"))
            CaseOutcome(name, verdict) -> decided
          case Some(error) =>
            CaseOutcome(name, Verdict.Skipped(SkipReason.RuleNotBuilt(shortReason(error)))) -> None
          case None =>
            val verdict = derived match
              case Right(_)      => Verdict.Agreed
              case Left(refusal) => Verdict.Diverged(Vector(refusal.toString))
            CaseOutcome(name, verdict) -> decided

  /** The exception family rather than its whole name, so the skip counts group
    * into something a reader can act on.
    */
  private def shortReason(error: String): String =
    error.takeWhile(_ != '.').takeWhile(_ != '|')

  private def decodeRequest(entry: Json): Either[String, NewPayloadRequest] =
    entry.hcursor.downField("params").downN(0).focus match
      case None          => Left("no executionPayload in params")
      case Some(payload) => decodePayload(payload).map(NewPayloadRequest(_))

  private def decodePayload(payload: Json): Either[String, ExecutionPayload] =
    val cursor = payload.hcursor
    for
      parentHash <- hashAt(cursor, "parentHash")
      feeRecipient <- addressAt(cursor, "feeRecipient")
      stateRoot <- hashAt(cursor, "stateRoot")
      receiptsRoot <- hashAt(cursor, "receiptsRoot")
      logsBloom <- stringAt(cursor, "logsBloom").flatMap(s => Bloom.fromHex(s).left.map(_ => "bad logsBloom"))
      prevRandao <- hashAt(cursor, "prevRandao")
      blockNumber <- quantityAt(cursor, "blockNumber")
      gasLimit <- quantityAt(cursor, "gasLimit")
      gasUsed <- quantityAt(cursor, "gasUsed")
      timestamp <- quantityAt(cursor, "timestamp")
      extraData <- stringAt(cursor, "extraData").flatMap(s => Bytes.fromHex(s).left.map(_ => "bad extraData"))
      baseFeePerGas <- wideQuantityAt(cursor, "baseFeePerGas")
      blockHash <- hashAt(cursor, "blockHash")
      transactions <- transactionsAt(cursor)
      withdrawals <- withdrawalsAt(cursor)
    yield ExecutionPayload(
      parentHash = parentHash,
      feeRecipient = feeRecipient,
      stateRoot = stateRoot,
      receiptsRoot = receiptsRoot,
      logsBloom = logsBloom,
      prevRandao = prevRandao,
      blockNumber = blockNumber,
      gasLimit = gasLimit,
      gasUsed = gasUsed,
      timestamp = timestamp,
      extraData = extraData,
      baseFeePerGas = baseFeePerGas,
      blockHash = blockHash,
      transactions = transactions,
      appended = withdrawals.map(PayloadWithdrawals(_))
    )

  private def transactionsAt(cursor: io.circe.HCursor): Either[String, Seq[Bytes]] =
    cursor.downField("transactions").as[Vector[String]].left.map(_ => "bad transactions").flatMap { encoded =>
      val decoded = encoded.map(s => Bytes.fromHex(s).left.map(_ => "bad transaction"))
      decoded.collectFirst { case Left(reason) => reason }.toLeft(decoded.collect { case Right(tx) => tx })
    }

  /** The withdrawal list, absent where the payload predates the proposal.
    *
    * The field is missing from a Paris payload and present from Shanghai on, and
    * the two are different facts rather than an empty list either way — which is
    * what makes this the one place the tier exercises the appended chain.
    */
  private def withdrawalsAt(cursor: io.circe.HCursor): Either[String, Option[Seq[Withdrawal]]] =
    cursor.downField("withdrawals").focus match
      case None       => Right(None)
      case Some(json) =>
        json.as[Vector[Json]].left.map(_ => "bad withdrawals").flatMap { entries =>
          val decoded = entries.map(decodeWithdrawal)
          decoded
            .collectFirst { case Left(reason) => reason }
            .toLeft(Some(decoded.collect { case Right(w) => w }))
        }

  private def decodeWithdrawal(json: Json): Either[String, Withdrawal] =
    val cursor = json.hcursor
    for
      index <- quantityAt(cursor, "index")
      validatorIndex <- quantityAt(cursor, "validatorIndex")
      address <- addressAt(cursor, "address")
      amount <- quantityAt(cursor, "amount")
    yield Withdrawal(index, validatorIndex, address, amount)

  private def stringAt(cursor: io.circe.HCursor, field: String): Either[String, String] =
    cursor.downField(field).as[String].left.map(_ => "missing " + field)

  private def hashAt(cursor: io.circe.HCursor, field: String): Either[String, Hash] =
    stringAt(cursor, field).flatMap(s => Hash.fromHex(s).left.map(_ => "bad " + field))

  private def addressAt(cursor: io.circe.HCursor, field: String): Either[String, Address] =
    stringAt(cursor, field).flatMap(s => Address.fromHex(s).left.map(_ => "bad " + field))

  private def quantityAt(cursor: io.circe.HCursor, field: String): Either[String, UInt64] =
    stringAt(cursor, field).flatMap(s => UInt64.fromHex(evenBodied(s)).left.map(_ => "bad " + field))

  private def wideQuantityAt(cursor: io.circe.HCursor, field: String): Either[String, UInt256] =
    stringAt(cursor, field).flatMap(s => UInt256.fromHex(evenBodied(s)).left.map(_ => "bad " + field))

  /** A `QUANTITY` as the Engine API writes it, left-padded to a whole byte.
    *
    * ==A carrier concern met inside a harness, and it belongs to neither side==
    *
    * The Engine API writes a quantity in minimal hex, so `0x1` and `0x7270e00`
    * are both well-formed on the wire and both have an odd number of digits.
    * `org.fukuii.bytes.Hex` refuses an odd-length body outright, deliberately —
    * a decoder that pads an unexpected input turns a corrupt value into a
    * plausible one.
    *
    * So something has to bridge them, and it is whatever decodes the JSON
    * rather than any domain type. This harness decodes JSON, so it bridges it
    * here; a transport layer will bridge the same gap for the same reason.
    */
  private def evenBodied(hex: String): String =
    val body = if hex.startsWith("0x") then hex.substring(2) else hex
    if body.length % 2 == 0 then "0x" + body else "0x0" + body

/** What a label's decided payloads actually reached.
  *
  * ==Counted because agreement is broad and reach is not==
  *
  * Every payload derives a header, so a tier reporting only agreement reads the
  * same whether the corpus exercises a commitment deeply or not at all. These
  * are the two commitments this layer derives rather than copies, and the
  * numbers behind them are uneven enough that stating them changes what the
  * tier is understood to have shown.
  *
  * @param decided
  *   payloads whose outcome the tier asserted, which excludes the skipped ones.
  * @param withTransactions
  *   payloads carrying at least one transaction, so the transactions root is a
  *   real trie rather than the empty one.
  * @param withWithdrawalsField
  *   payloads carrying the field at all, which is what makes the header's tail
  *   one link deeper.
  * @param withNonEmptyWithdrawals
  *   payloads carrying at least one withdrawal, so the withdrawals root is a
  *   real trie. **This is the thin one**, and it is the reason the number is
  *   here rather than left to be assumed from the label's size.
  */
final case class Coverage(
    decided: Int,
    withTransactions: Int,
    withWithdrawalsField: Int,
    withNonEmptyWithdrawals: Int
):

  def observing(payload: ExecutionPayload): Coverage =
    val withdrawals = payload.withdrawals
    Coverage(
      decided = decided + 1,
      withTransactions = withTransactions + (if payload.transactions.nonEmpty then 1 else 0),
      withWithdrawalsField = withWithdrawalsField + (if withdrawals.isDefined then 1 else 0),
      withNonEmptyWithdrawals = withNonEmptyWithdrawals + (if withdrawals.exists(_.nonEmpty) then 1 else 0)
    )

object Coverage:
  val Empty: Coverage = Coverage(0, 0, 0, 0)
