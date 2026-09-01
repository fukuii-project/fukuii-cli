package org.fukuii.evm.fixtures

import java.nio.file.{Files, Path, Paths}

import scala.jdk.CollectionConverters.given
import scala.util.Using
import scala.util.control.NonFatal

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

  /** The file, or one case inside it, did not decode. */
  case Undecodable(detail: String)

  /** The case states an expectation that depends on a rule this build does not
    * implement, so running it would compare an answer against a question that
    * was never asked.
    *
    * ==Distinct from having no expectation, because the fixture states one==
    *
    * [[NoExpectationAtThisFork]] is the corpus declining to say anything. This
    * is the corpus saying something definite that this build has no machinery
    * to reach, which is a gap on this side rather than on the corpus's -- so it
    * carries what is missing, and a count under this reason is the size of the
    * work that would close it.
    *
    * **A divergence would be the wrong verdict and a silent omission worse.** An
    * unimplemented rule reported as a divergence is indistinguishable from a
    * rule implemented wrongly, and a case dropped from the run altogether lets a
    * harness narrow its own input until it agrees with everything left.
    */
  case RuleNotBuilt(detail: String)

  /** The case supplies an input the rule under test is not stated over, so the
    * rule refuses it rather than answering.
    *
    * ==Distinct from a rule this build lacks, and from a divergence==
    *
    * [[RuleNotBuilt]] is a gap on this side. This is neither side's gap: the
    * rule exists, the corpus states a case outside what the rule is defined
    * for, and the refusal is the rule working. Counting it as a divergence
    * would report a defect where the build is behaving as documented, and
    * dropping it would let a harness narrow its own input until it agreed with
    * everything left.
    *
    * **The detail carries what the rule refused**, because a reason that only
    * says "refused" cannot be told from a rule refusing everything.
    */
  case InputRefused(detail: String)

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
    case SkipReason.NoExpectationAtThisFork => "no-expectation-at-this-fork"
    case SkipReason.Undecodable(_)          => "undecodable"
    case SkipReason.RuleNotBuilt(_)         => "rule-not-built"
    case SkipReason.InputRefused(_)         => "input-refused"

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

  /** Ethereum Classic's own published state tier, in the same retesteth
    * `GeneralStateTest` shape the two corpora above are read in.
    *
    * ==A third tree, and deliberately not a third root==
    *
    * [[root]] resolves to the directory holding one subdirectory per upstream
    * organization, so a tree published by another organization is a `resolve`
    * beside the two above rather than a second pointer to configure. A root of
    * its own would give this tier a failure mode the others do not have, for no
    * gain: the tree is third-party material fetched and never written, which is
    * exactly what this root already locates.
    *
    * ==What makes it worth reading, which is a property of its transactions==
    *
    * Every entry this build reads here is signed under EIP-155 naming chain 61,
    * so no other corpus available to this build can stand in for it: a tier
    * whose transactions name no chain is satisfiable by any network's rules,
    * and one naming another chain would be refused before its rules were
    * reached. That is the whole of why this tree is registered and the
    * unprotected tiers of the same shape are not.
    */
  def classicPublished(under: Path): Path =
    under.resolve("etclabscore/tests-etc/GeneralStateTests")

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

  /** The file's text, or the reason it could not be had.
    *
    * ==Why this returns an Either where it used to throw==
    *
    * `Files.readString` throws on input that is not valid UTF-8, and the module
    * had no exception boundary anywhere -- so a truncated download, a
    * partially-written file, or a `.json` that is actually gzip did not become
    * one counted skip. It aborted every test in the suite with a stack trace.
    *
    * That is narrower than it sounds and worse than it sounds at once: the skip
    * taxonomy already has [[SkipReason.Undecodable]], but only a PARSE failure
    * reached it, because circe returns those as a value. The likeliest real
    * failure -- a file that cannot be read as text at all -- took a different
    * path out of the module entirely.
    *
    * `NonFatal` deliberately does not catch an `OutOfMemoryError`, which a file
    * above 2 GB would raise. That one should fail loudly and stop the run.
    */
  def read(path: Path): Either[String, String] =
    try Right(Files.readString(path))
    catch case NonFatal(cause) => Left("unreadable: " + cause.getClass.getSimpleName)
