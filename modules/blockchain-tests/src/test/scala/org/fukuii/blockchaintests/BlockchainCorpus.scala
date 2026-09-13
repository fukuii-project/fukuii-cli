package org.fukuii.blockchaintests

import java.nio.file.Path

import org.fukuii.consensus.RuleNotRun
import org.fukuii.evm.fixtures.{CaseOutcome, CorpusReport, FixtureCorpus, SkipReason, Verdict}

/** What a case states it holds, read before it is run.
  *
  * Kept beside the result so a report can set what the corpus states against
  * what this build did with it: a count of accepted blocks means nothing without
  * the count the corpus says there are.
  *
  * @param refusals
  *   blocks the case states invalid. At most one per case in every label this
  *   runner is built for; `for_praguetoosakaattime15k` states up to three.
  */
final case class StatedShape(validBlocks: Int, refusals: Int)

object StatedShape:

  def of(fixture: BlockchainFixture): StatedShape =
    val refusals = fixture.blocks.count {
      case StatedBlock.Invalid(_, _, _) => true
      case StatedBlock.Valid(_, _)      => false
    }
    StatedShape(fixture.blocks.length - refusals, refusals)

  /** A case this reader did not decode, which states nothing it can count. */
  val Unread: StatedShape = StatedShape(0, 0)

final case class BlockchainOutcome(name: String, stated: StatedShape, result: BlockchainResult)

/** What one label produced, as the counts its certification is pinned to.
  *
  * ==Why not `org.fukuii.evm.fixtures.CorpusReport`==
  *
  * Its verdict has three answers and this tier has four: a block reaching a rule
  * this build does not run is neither an agreement, a divergence nor a skip, and
  * folding it into any of the three would hide the one count that measures what
  * stands between this build and a verdict. **What it does fit is reused
  * rather than copied**: the skip reasons are its, their labels are the ones it
  * counts skips under, and a failure shows as many divergences as it does.
  *
  * `filesRead` is recorded apart from the outcomes for that report's reason: a
  * label that loaded nothing must not read as a label that found nothing wrong.
  */
final case class BlockchainReport(corpus: String, filesRead: Int, outcomes: Vector[BlockchainOutcome]):

  def cases: Int = outcomes.length

  def blocksStatedValid: Int = outcomes.map(_.stated.validBlocks).sum

  def refusalsStated: Int = outcomes.map(_.stated.refusals).sum

  def blocksAccepted: Int = outcomes.map(_.result.blocksAccepted).sum

  def agreed: Vector[BlockchainOutcome] = outcomes.filter(_.result.verdict == BlockchainVerdict.Agreed)

  /** Blocks this build refused under a name their case states, as the runner
    * counted them where the refusal happened.
    */
  def refusalsAgreed: Int = outcomes.map(_.result.refusalsAgreed).sum

  def diverged: Vector[BlockchainOutcome] =
    outcomes.filter { outcome =>
      outcome.result.verdict match
        case BlockchainVerdict.Diverged(_) => true
        case _                             => false
    }

  def undecidedByRule: Map[String, Int] =
    tally(outcomes.map(_.result.verdict).collect { case BlockchainVerdict.Undecided(_, rule) => ruleName(rule) })

  /** Skipped cases under the labels
    * [[org.fukuii.evm.fixtures.CorpusReport.skipsByReason]] gives them, counted
    * by that report itself so the labels have one definition.
    */
  def skippedByReason: Map[String, Int] =
    val skips = outcomes.collect {
      case BlockchainOutcome(name, _, BlockchainResult(BlockchainVerdict.Skipped(reason), _, _)) =>
        CaseOutcome(name, Verdict.Skipped(reason))
    }
    CorpusReport(corpus, filesRead, skips).skipsByReason

  private def tally(names: Vector[String]): Map[String, Int] =
    names.groupBy(identity).view.mapValues(_.length).toMap

  /** The report as the facts a failure is read from, each divergence with its
    * reasons.
    */
  def describe: String =
    val head =
      corpus + ": files=" + filesRead.toString + " cases=" + cases.toString + " blocksStatedValid=" +
        blocksStatedValid.toString + " blocksAccepted=" + blocksAccepted.toString + " refusalsStated=" +
        refusalsStated.toString + " refusalsAgreed=" + refusalsAgreed.toString + " agreed=" + agreed.length.toString +
        " diverged=" + diverged.length.toString + " undecided=" + undecidedByRule.toSeq.sorted.mkString(",") +
        " skipped=" + skippedByReason.toSeq.sorted.mkString(",")
    val detail = diverged.take(CorpusReport.DivergencesShown).map { outcome =>
      val reasons = outcome.result.verdict match
        case BlockchainVerdict.Diverged(stated) => stated.mkString("; ")
        case _                                  => ""
      "\n  " + outcome.name + ": " + reasons
    }
    head + detail.mkString

  private def ruleName(rule: RuleNotRun): String = rule match
    case RuleNotRun.Operation(gap)         => "operation " + gap.opcode.toString
    case RuleNotRun.OmmerValidation(_)     => "ommer-validation"
    case RuleNotRun.EngineRules(engineSet) => "engine-rules " + engineSet.toVector.map(_.toString).sorted.mkString("+")

/** The published `blockchain_tests` tier, run a label at a time.
  *
  * ==Folded a file at a time, so nothing accumulates but outcomes==
  *
  * A label is tens of megabytes of JSON carrying every block's encoding. A
  * file's cases are decoded, run and reduced to their outcomes before the next
  * file is read, so the cost held at once is one file's.
  */
object BlockchainCorpus:

  /** The labels certified, as the release names their directories. */
  val Labels: Vector[String] = Vector("for_paris")

  def directory(root: Path, label: String): Path =
    FixtureCorpus.generated(root).resolve("blockchain_tests").resolve(label)

  /** One report per label, or nothing at all when the corpus cannot be located.
    *
    * `None` rather than empty reports, because a harness answering with empty
    * reports is indistinguishable from one that found nothing wrong.
    */
  lazy val reports: Option[Vector[BlockchainReport]] =
    FixtureCorpus.root.map(root => Labels.map(label => report(root, label)))

  def report(root: Path, label: String): BlockchainReport =
    val base = directory(root, label)
    val files = FixtureCorpus.jsonFilesUnder(base)
    BlockchainReport(label, files.length, files.flatMap(file => outcomesIn(base, file)))

  /** The cases one file under a label decodes to, for a caller that reads a
    * named file rather than a whole label.
    */
  def casesIn(root: Path, label: String, relative: String): Either[String, BlockchainFile] =
    val file = directory(root, label).resolve(relative)
    FixtureCorpus.read(file).flatMap(BlockchainFixture.decodeFile(relative, _))

  private def outcomesIn(base: Path, file: Path): Vector[BlockchainOutcome] =
    val relative = base.relativize(file).toString
    FixtureCorpus.read(file).flatMap(BlockchainFixture.decodeFile(relative, _)) match
      case Left(error) =>
        Vector(unread(relative, error))
      case Right(decoded) =>
        decoded.undecodable.map((name, reason) => unread(relative + " " + name, reason)) ++
          decoded.fixtures.map { fixture =>
            BlockchainOutcome(relative + " " + fixture.name, StatedShape.of(fixture), BlockchainRunner.run(fixture))
          }

  private def unread(name: String, reason: String): BlockchainOutcome =
    BlockchainOutcome(
      name,
      StatedShape.Unread,
      BlockchainResult(
        BlockchainVerdict.Skipped(SkipReason.Undecodable(reason)),
        blocksAccepted = 0,
        refusalsAgreed = 0
      )
    )
