package org.fukuii.consensus.pos.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path

import io.circe.Json

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.consensus.pos.*
import org.fukuii.types.{BlockHeader, Bloom, Withdrawal}

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
  /** The translation each run classifies the identical corpus under.
    *
    * ==A tier only ever seen agreeing is not evidence that it can disagree==
    *
    * [[AsBuilt]] is this build. The other two are deliberately wrong
    * translations of the same input, and requiring an EXACT divergence count
    * from each is what makes the agreement mean something: a tier that had
    * quietly stopped deriving anything would report zero divergences under all
    * three.
    *
    * ==Why they live here rather than in a commit message==
    *
    * The alternative is to edit the derivation, run the suite, write the
    * numbers down and revert — which measures the tier once and leaves nothing
    * that fails when the property stops holding. Here the arms are re-run on
    * every pass, and an arm that stops diverging by the stated amount is a
    * failure rather than a paragraph nobody re-reads.
    *
    * ==Both are cheap, because the parse is shared==
    *
    * Every arm classifies the same decoded entries in the same pass over the
    * files, so an arm costs a re-derivation rather than another read of eighty
    * megabytes of JSON.
    */
  enum Arm:

    /** This build's translation, which is what the tier certifies. */
    case AsBuilt

    /** One withdrawal dropped from the payload before the root is taken.
      *
      * Deliberately the NARROWEST defect this corpus can see. It leaves the
      * empty-list case identical, so it moves only the payloads carrying a real
      * withdrawals list — which is the thin commitment the coverage assertions
      * pin, measured rather than assumed.
      */
    case WithdrawalDropped

    /** The ommers hash replaced by the parent hash after derivation.
      *
      * The opposite end: a constant every payload commits to, so almost nothing
      * survives it. The two arms bracket the range a wrong header can fail
      * across — one commitment reached by a few dozen payloads, one reached by
      * all of them.
      */
    case OmmersSubstituted

  private val Arms: Vector[Arm] = Arm.values.toVector

  /** One report per label under this build's own translation, or nothing at all
    * when the corpus cannot be located.
    *
    * `None` rather than empty reports, for the reason the corpora above it
    * give: a harness answering with empty reports is indistinguishable from one
    * that found nothing wrong. The spec beside this turns `None` into a loud
    * failure rather than a cancellation, because a canceled case is counted by
    * nothing and a run whose corpus vanished would otherwise report the same
    * total as one that read it.
    */
  lazy val reports: Option[Vector[CorpusReport]] = reportsUnder(Arm.AsBuilt).map(_.map(_._1))

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
    reportsUnder(Arm.AsBuilt).map(_.map((report, cover) => report.corpus -> cover))

  /** One report per label under one arm, for the spec to count divergences in. */
  def reportsUnder(arm: Arm): Option[Vector[(CorpusReport, Coverage)]] = runs.map(_.map(_(arm)))

  private lazy val runs: Option[Vector[Map[Arm, (CorpusReport, Coverage)]]] =
    FixtureCorpus.root.map(root => Labels.map(label => classify(root, label)))

  /** One label, read once and classified under every arm.
    *
    * ==Folded per file, so nothing accumulates but counts==
    *
    * A label decodes to thousands of payloads carrying every transaction byte
    * the corpus states. Holding them all to run three translations over them
    * would trade a bounded cost for an unbounded one, so a file's entries are
    * decoded, classified under all three arms, and dropped — which keeps the
    * single parse without retaining what it parsed.
    */
  private def classify(root: Path, label: String): Map[Arm, (CorpusReport, Coverage)] =
    val files = FixtureCorpus.jsonFilesUnder(directory(root, label))
    val tallied = files.foldLeft(Arms.map(_ -> Tally.Empty).toMap) { (acc, file) =>
      val entries = entriesIn(file)
      acc.map((arm, tally) => arm -> tally.observing(entries.map(entry => outcomeOf(entry, translationFor(arm)))))
    }
    tallied.map((arm, tally) => arm -> (CorpusReport(label, files.length, tally.outcomes), tally.coverage))

  private def translationFor(arm: Arm): NewPayloadRequest => Either[TranslationRefusal, BlockHeader] =
    arm match
      case Arm.AsBuilt           => PayloadTranslation.checkedHeaderOf
      case Arm.WithdrawalDropped => request => PayloadTranslation.checkedHeaderOf(withoutLastWithdrawal(request))
      case Arm.OmmersSubstituted =>
        request => checkedAgainstStatedHash(request, _.copy(ommersHash = request.payload.parentHash))

  private def withoutLastWithdrawal(request: NewPayloadRequest): NewPayloadRequest =
    val payload = request.payload
    val shortened = payload.appended.map(appended => appended.copy(withdrawals = appended.withdrawals.dropRight(1)))
    request.copy(payload = payload.copy(appended = shortened))

  /** The derivation, damaged after the fact, then checked against the hash the
    * payload states — which is what [[PayloadTranslation.checkedHeaderOf]] does
    * and is repeated here rather than reused, because reusing it would apply
    * the damage before the check rather than between the two.
    */
  private def checkedAgainstStatedHash(
      request: NewPayloadRequest,
      damage: BlockHeader => BlockHeader
  ): Either[TranslationRefusal, BlockHeader] =
    PayloadTranslation.headerOf(request).map(damage).flatMap { header =>
      if header.hash == request.payload.blockHash then Right(header)
      else Left(TranslationRefusal.BlockHashMismatch(request.payload.blockHash, header.hash))
    }

  private def entriesIn(file: Path): Vector[LoadedEntry] =
    FixtureCorpus.read(file) match
      case Left(reason) => Vector(LoadedEntry(file.getFileName.toString, Left("unreadable: " + reason), None))
      case Right(text)  => entriesOf(file.getFileName.toString, text)

  private def entriesOf(fileName: String, text: String): Vector[LoadedEntry] =
    io.circe.parser.parse(text) match
      case Left(failure) => Vector(LoadedEntry(fileName, Left(failure.getMessage), None))
      case Right(json)   =>
        json.asObject
          .map(_.toVector.flatMap((name, body) => casesOf(name, body)))
          .getOrElse(Vector(LoadedEntry(fileName, Left("top level is not an object"), None)))

  private def casesOf(name: String, body: Json): Vector[LoadedEntry] =
    body.hcursor.downField("engineNewPayloads").as[Vector[Json]] match
      case Left(_)        => Vector(LoadedEntry(name, Left("no engineNewPayloads array"), None))
      case Right(entries) =>
        entries.zipWithIndex.map { (entry, index) =>
          LoadedEntry(
            name + "#" + index.toString,
            decodeRequest(entry),
            entry.hcursor.downField("validationError").as[String].toOption
          )
        }

  /** What running one published payload established.
    *
    * ==Three groups, and only two of them are assertions about VALIDITY==
    *
    * A payload the corpus expects to be accepted must derive a header that
    * hashes to what it states. A payload the corpus expects to be rejected FOR
    * ITS BLOCK HASH must be refused, which is the only published exercise of
    * the refusal path this tier has.
    *
    * **Everything else the corpus expects to be rejected is skipped**, because
    * those name a transaction-level defect or a block-level gas rule that this
    * layer does not reach, and calling such a payload valid or invalid would be
    * answering a question the corpus asked of a different layer.
    *
    * ==The skipped ones are still TRANSLATED, and the answer is no longer
    * thrown away==
    *
    * The corpus states a `blockHash` for every one of them, and that commitment
    * is about this layer exactly as much as it is for the payloads above.
    * Discarding the derivation left a region of the input on which the tier's
    * central assertion could not fail — a translation defect reachable only
    * from the malformed transaction shapes those fixtures carry would have
    * produced a wrong header for precisely them, and this suite would have
    * stayed green.
    *
    * **The block-hash claim is orthogonal to the validity claim the corpus
    * declines to make**, so recording it says nothing the corpus did not. The
    * verdict stays `Skipped`; what changes is that [[Coverage]] now counts what
    * the translation answered, and the spec pins the split.
    */
  private def outcomeOf(
      entry: LoadedEntry,
      translate: NewPayloadRequest => Either[TranslationRefusal, BlockHeader]
  ): (CaseOutcome, Reach) =
    entry.decoded match
      case Left(reason) =>
        CaseOutcome(entry.name, Verdict.Skipped(SkipReason.Undecodable(reason))) -> Reach.NotDecoded
      case Right(request) =>
        val derived = translate(request)
        entry.expected match
          case Some(error) if error.contains("INVALID_BLOCK_HASH") =>
            val verdict = derived match
              case Left(TranslationRefusal.BlockHashMismatch(_, _)) => Verdict.Agreed
              case Left(other)                                      =>
                Verdict.Diverged(Vector("expected a block-hash refusal, refused " + other.toString))
              case Right(_) =>
                Verdict.Diverged(Vector("expected a block-hash refusal, derived a matching header"))
            CaseOutcome(entry.name, verdict) -> Reach.Decided(request.payload)
          case Some(error) =>
            val shadow: UndecidedTranslation = derived match
              case Right(_) => UndecidedTranslation.DerivedHeader
              case Left(_)  => UndecidedTranslation.Refused
            CaseOutcome(entry.name, Verdict.Skipped(SkipReason.RuleNotBuilt(shortReason(error)))) ->
              Reach.Undecided(shadow)
          case None =>
            val verdict = derived match
              case Right(_)      => Verdict.Agreed
              case Left(refusal) => Verdict.Diverged(Vector(refusal.toString))
            CaseOutcome(entry.name, verdict) -> Reach.Decided(request.payload)

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

/** One published payload, decoded once, whatever translations are run over it.
  *
  * The corpus is eighty megabytes of JSON and the calibration arms drive it
  * more than once, so the parse is separated from the classification: reading
  * and decoding happen in [[EnginePayloadCorpus.loaded]], and a second arm
  * costs a re-derivation rather than a second pass over the files.
  *
  * @param expected
  *   the `validationError` the corpus states, absent where it expects the
  *   payload to be accepted.
  */
final case class LoadedEntry(
    name: String,
    decoded: Either[String, NewPayloadRequest],
    expected: Option[String]
)

/** One arm's running state while a label's files are folded over.
  *
  * Separate from [[Coverage]] because the verdicts and the reach counts are
  * accumulated together and reported apart, and a bare pair of them at every
  * call site is what a named accumulator exists to avoid.
  */
final case class Tally(outcomes: Vector[CaseOutcome], coverage: Coverage):

  def observing(batch: Vector[(CaseOutcome, Reach)]): Tally =
    Tally(outcomes ++ batch.map(_._1), batch.foldLeft(coverage)((seen, next) => seen.observing(next._2)))

object Tally:
  val Empty: Tally = Tally(Vector.empty, Coverage.Empty)

/** How far one payload carried the tier, which is not what the verdict says.
  *
  * ==A verdict answers whether the tier agreed; this answers what it touched==
  *
  * A payload the corpus expects to be rejected for a rule this layer does not
  * reach is `Skipped` — the tier declines to call it valid or invalid, because
  * the corpus asked that question of a different layer. **It was still
  * translated, and the block hash it states is still a commitment about this
  * layer.** [[Undecided]] is what carries that answer out to be counted, so
  * that declining to decide validity no longer discards the derivation too.
  */
enum Reach:

  /** The harness could not decode it, which is a reader fault and not a corpus
    * one. The spec asserts this never happens.
    */
  case NotDecoded

  /** The tier asserted this payload's outcome, and coverage counts it. */
  case Decided(payload: ExecutionPayload)

  /** The tier declined to decide validity and translated it anyway. */
  case Undecided(translation: UndecidedTranslation)

/** What the translation answered for a payload whose validity was not decided.
  *
  * Two cases rather than a boolean, because the counts are asserted separately
  * and a transposed boolean at a call site reads as correct — the reason
  * `.claude/rules/scala3-style.md` gives for modeling a state with a type.
  */
enum UndecidedTranslation:
  case DerivedHeader
  case Refused

/** What a label's payloads actually reached.
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
  * @param undecidedDerivingHeader
  *   payloads whose validity the tier declined to decide, whose stated block
  *   hash the derived header nonetheless reproduced.
  * @param undecidedRefused
  *   the same set, refused instead. **Nothing about the corpus implies this is
  *   zero** — these payloads carry the malformed transaction shapes the corpus
  *   was built to reject, so they are the likeliest input to break a
  *   translation. That is what makes the number worth asserting rather than
  *   assuming.
  */
final case class Coverage(
    decided: Int,
    withTransactions: Int,
    withWithdrawalsField: Int,
    withNonEmptyWithdrawals: Int,
    undecidedDerivingHeader: Int,
    undecidedRefused: Int
):

  def observing(reach: Reach): Coverage =
    reach match
      case Reach.NotDecoded                                    => this
      case Reach.Undecided(UndecidedTranslation.DerivedHeader) =>
        copy(undecidedDerivingHeader = undecidedDerivingHeader + 1)
      case Reach.Undecided(UndecidedTranslation.Refused) =>
        copy(undecidedRefused = undecidedRefused + 1)
      case Reach.Decided(payload) =>
        val withdrawals = payload.withdrawals
        copy(
          decided = decided + 1,
          withTransactions = withTransactions + (if payload.transactions.nonEmpty then 1 else 0),
          withWithdrawalsField = withWithdrawalsField + (if withdrawals.isDefined then 1 else 0),
          withNonEmptyWithdrawals = withNonEmptyWithdrawals + (if withdrawals.exists(_.nonEmpty) then 1 else 0)
        )

object Coverage:
  val Empty: Coverage = Coverage(0, 0, 0, 0, 0, 0)
