package org.fukuii.evm.fixtures

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.given
import scala.util.Using

/** Why one fixture in a corpus was not executed.
  *
  * A skip is data rather than an omission: a harness that quietly narrows its
  * own input reports conformance it never measured, so every case that does not
  * run carries a reason here and is counted under it.
  */
enum SkipReason:
  /** The fixture records no expectation for this fork, so there is nothing here
    * to agree or disagree with.
    */
  case NoExpectationAtThisFork

  /** The transaction deploys code rather than calling an account, and this
    * layer publishes no entry point for that: a deployment reached from a
    * transaction is assembled by whatever ends the transaction, which is a
    * layer above this one.
    */
  case TransactionLevelCreation

  /** The file, or one case inside it, did not decode. */
  case Undecodable(detail: String)

/** What running one fixture established. */
enum Verdict:
  case Agreed
  case Diverged(reasons: Vector[String])
  case Skipped(reason: SkipReason)

/** One fixture's name and what running it established. */
final case class CaseOutcome(name: String, verdict: Verdict)

/** What a whole corpus produced, as the counts a coverage claim is made from.
  *
  * `filesRead` and `casesFound` are recorded separately from `agreed` so that a
  * corpus which loaded nothing cannot be reported as a corpus that found
  * nothing to disagree with.
  */
final case class CorpusReport(corpus: String, filesRead: Int, outcomes: Vector[CaseOutcome]):

  def casesFound: Int = outcomes.length

  def agreed: Vector[CaseOutcome] = outcomes.filter(_.verdict == Verdict.Agreed)

  def diverged: Vector[CaseOutcome] = outcomes.collect { case o @ CaseOutcome(_, Verdict.Diverged(_)) => o }

  def skipped: Vector[CaseOutcome] = outcomes.collect { case o @ CaseOutcome(_, Verdict.Skipped(_)) => o }

  def skipsByReason: Map[String, Int] =
    outcomes
      .collect { case CaseOutcome(_, Verdict.Skipped(reason)) => label(reason) }
      .groupBy(identity)
      .view
      .mapValues(_.length)
      .toMap

  /** The report as one line per fact, which is what a divergence is read from
    * when a test fails.
    */
  def describe: String =
    val head =
      s"$corpus: files=$filesRead cases=$casesFound agreed=${agreed.length} " +
        s"diverged=${diverged.length} skipped=${skipped.length} skips=${skipsByReason.toSeq.sorted.mkString(",")}"
    val detail = diverged.take(CorpusReport.DivergencesShown).map { outcome =>
      val reasons = outcome.verdict match
        case Verdict.Diverged(rs) => rs.mkString("; ")
        case _                    => ""
      s"\n  ${outcome.name}: $reasons"
    }
    head + detail.mkString

  private def label(reason: SkipReason): String = reason match
    case SkipReason.NoExpectationAtThisFork  => "no-expectation-at-this-fork"
    case SkipReason.TransactionLevelCreation => "transaction-level-creation"
    case SkipReason.Undecodable(_)           => "undecodable"

object CorpusReport:

  /** Enough divergences to characterize a failure without a clue running to
    * megabytes.
    */
  val DivergencesShown: Int = 12

/** Where the published fixture corpora are, and which files inside them this
  * fork is certified against.
  *
  * ==The corpora are not in this repository, and cannot be==
  *
  * `ethereum/legacytests` and the `tests@v20.0.1` release of
  * `ethereum/execution-specs-fixtures` are third-party artifacts of tens of
  * megabytes. They are named by the corpus manifest and assembled beside a
  * clone rather than inside it, so their location arrives as an environment
  * variable and a run without one is reported as a run that measured nothing
  * rather than as a run that found nothing wrong.
  */
object FixtureCorpus:

  /** The directory holding one subdirectory per upstream organization, which is
    * the layout the corpus manifest specifies.
    */
  val RootVariable: String = "FUKUII_FIXTURE_ROOT"

  /** A file holding that same path, read when the variable is not set.
    *
    * The variable alone is not enough, and the reason is a property of the
    * build tool rather than a convenience: sbt keeps a detached server, and a
    * task run through a server that was already running sees the environment
    * that server was started with, not the one the invocation carries. A file
    * is read at the moment the corpus is wanted and cannot go stale that way.
    * It sits under an ignored directory, so no machine path reaches a clone.
    */
  val RootPointer: Path = Paths.get(".local/fixture-corpus-root")

  def root: Option[Path] =
    sys.env
      .get(RootVariable)
      .orElse(sys.props.get(RootVariable))
      .orElse(pointed)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .filter(Files.isDirectory(_))

  private def pointed: Option[String] =
    if Files.isRegularFile(RootPointer) then Some(Files.readString(RootPointer)) else None

  /** The legacy hand-written corpus, whose `Constantinople` directory is a
    * snapshot of `ethereum/tests` filled for Frontier through ConstantinopleFix.
    */
  def legacy(under: Path): Path = under.resolve("ethereum/legacytests/Constantinople")

  /** The generated corpus, at the release tag rather than at a branch. */
  def generated(under: Path): Path =
    under.resolve("ethereum/execution-specs-fixtures/tests-v20.0.1/fixtures")

  /** Every `.json` beneath `directory`, in a stable order so two runs report
    * the same thing.
    */
  def jsonFilesUnder(directory: Path): Vector[Path] =
    if !Files.isDirectory(directory) then Vector.empty
    else
      Using.resource(Files.walk(directory)) { walk =>
        walk
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".json"))
          .toVector
          .sortBy(_.toString)
      }

  def read(path: Path): String = Files.readString(path)
