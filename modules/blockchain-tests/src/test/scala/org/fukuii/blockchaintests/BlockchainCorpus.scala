package org.fukuii.blockchaintests

import java.nio.file.Path

import io.circe.Json

import org.fukuii.consensus.RuleNotRun
import org.fukuii.evm.fixtures.{CaseOutcome, CorpusReport, FixtureCorpus, SkipReason, Verdict}

/** What a case states it holds, read before it is run.
  *
  * Kept beside the result so a report can set what the corpus states against
  * what this build did with it: a count of accepted blocks means nothing without
  * the count the corpus says there are.
  *
  * @param refusals
  *   blocks the case states invalid. At most one per case in every generated
  *   label this runner is built for; `for_praguetoosakaattime15k` states up to
  *   three, and `bcInvalidHeaderTest`'s `badTimestamp` three with blocks
  *   between and after them.
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

  /** The cases that diverged, by name, which is what a label pinned with its
    * divergences is held to: a count alone would let one divergence give way to
    * another and still read unchanged.
    */
  def divergedNames: Vector[String] = diverged.map(_.name).sorted

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

/** One label this tier certifies: where its files are, and which of their cases
  * it runs.
  *
  * @param directory
  *   the directory of files, under the corpus root.
  * @param excludedGroups
  *   the directories directly under [[directory]] whose files this label leaves
  *   to another. A generated label's `ported_static` is a re-pinned legacy suite
  *   that stays out of the ordinary run, and [[BlockchainCorpus.PortedStaticGroups]]
  *   reads it instead.
  * @param network
  *   the one network whose cases this label runs, where its files hold cases for
  *   several; every case in them where absent.
  * @param filledBy
  *   the tool whose names this label's refusals are stated in, which is a fact
  *   about the corpus the files sit in rather than about any one case.
  */
final case class BlockchainLabel(
    name: String,
    directory: Path => Path,
    excludedGroups: Set[String],
    network: Option[String],
    filledBy: FillingTool
):

  /** Whether a case, as its file writes it, is this label's. A case whose
    * network cannot be read is kept, so a broken case is counted rather than
    * dropped.
    */
  def keeps(body: Json): Boolean =
    network.forall(wanted => body.hcursor.downField("network").as[String].toOption.forall(_ == wanted))

/** The published `blockchain_tests` tier, run a label at a time.
  *
  * ==Folded a file at a time, so nothing accumulates but outcomes==
  *
  * A label is tens of megabytes of JSON carrying every block's encoding. A
  * file's cases are decoded, run and reduced to their outcomes before the next
  * file is read, so the cost held at once is one file's.
  *
  * ==Three corpora==
  *
  * The generated labels are directories of `ethereum/execution-specs-fixtures`'
  * `tests-v20.0.1` release, one set of rules each, and each is run without the
  * `ported_static` group it carries, where it carries one.
  * `bcInvalidHeaderTest` is `ethereum/legacytests`' directory of that name,
  * whose files each hold one case per network -- so each of its labels reads
  * every file and runs one network's cases. It is read because no case of the
  * generated release refuses a block for its state root, receipts root,
  * transactions root, logs bloom or gas used, and these do.
  *
  * **Two snapshots of it are read, filled by two tools**: the `Cancun` snapshot
  * runs Istanbul through Cancun and states retesteth's names, and the
  * `Constantinople` snapshot runs Frontier through ConstantinopleFix and states
  * testeth's. A label from the older snapshot names it; one from the `Cancun`
  * snapshot does not, which is how those labels were first named.
  */
object BlockchainCorpus:

  /** The directory a generated label holds its re-pinned legacy suite in, which
    * the label leaves to [[PortedStaticGroups]].
    */
  val PortedStatic: String = "ported_static"

  private def generated(label: String, excluding: Set[String] = Set.empty): BlockchainLabel =
    BlockchainLabel(
      label,
      root => FixtureCorpus.generated(root).resolve("blockchain_tests").resolve(label),
      excluding,
      None,
      FillingTool.ExecutionSpecs
    )

  private def invalidHeaders(network: String): BlockchainLabel =
    BlockchainLabel(
      "bcInvalidHeaderTest at " + network,
      _.resolve("ethereum/legacytests/Cancun/BlockchainTests/InvalidBlocks/bcInvalidHeaderTest"),
      Set.empty,
      Some(network),
      FillingTool.Retesteth
    )

  private def olderInvalidHeaders(network: String): BlockchainLabel =
    BlockchainLabel(
      "bcInvalidHeaderTest (Constantinople snapshot) at " + network,
      _.resolve("ethereum/legacytests/Constantinople/BlockchainTests/InvalidBlocks/bcInvalidHeaderTest"),
      Set.empty,
      Some(network),
      FillingTool.Testeth
    )

  /** The labels certified, in the order they run. */
  val Labels: Vector[BlockchainLabel] =
    Vector(
      generated("for_paris", excluding = Set(PortedStatic)),
      generated("for_shanghai", excluding = Set(PortedStatic)),
      generated("for_paristoshanghaiattime15k"),
      generated("for_shanghaitocancunattime15k"),
      generated("for_cancun", excluding = Set(PortedStatic)),
      generated("for_cancuntopragueattime15k"),
      generated("for_prague", excluding = Set(PortedStatic)),
      invalidHeaders("Paris"),
      invalidHeaders("Shanghai"),
      invalidHeaders("Cancun"),
      generated("for_frontier", excluding = Set(PortedStatic)),
      generated("for_homestead", excluding = Set(PortedStatic)),
      generated("for_tangerinewhistle", excluding = Set(PortedStatic)),
      generated("for_spuriousdragon", excluding = Set(PortedStatic)),
      generated("for_byzantium", excluding = Set(PortedStatic)),
      generated("for_constantinoplefix", excluding = Set(PortedStatic)),
      generated("for_istanbul", excluding = Set(PortedStatic)),
      generated("for_berlin", excluding = Set(PortedStatic)),
      generated("for_london", excluding = Set(PortedStatic)),
      olderInvalidHeaders("Frontier"),
      olderInvalidHeaders("Homestead"),
      olderInvalidHeaders("EIP150"),
      olderInvalidHeaders("EIP158"),
      olderInvalidHeaders("Byzantium"),
      olderInvalidHeaders("Constantinople"),
      olderInvalidHeaders("ConstantinopleFix"),
      invalidHeaders("Istanbul"),
      invalidHeaders("Berlin"),
      invalidHeaders("London")
    )

  def label(name: String): Option[BlockchainLabel] = Labels.find(_.name == name)

  /** One report per label, or nothing at all when the corpus cannot be located.
    *
    * `None` rather than empty reports, because a harness answering with empty
    * reports is indistinguishable from one that found nothing wrong.
    */
  lazy val reports: Option[Vector[BlockchainReport]] =
    FixtureCorpus.root.map(root => Labels.map(label => report(root, label)))

  /** Each generated label's `ported_static` group, as a label of its own, in the
    * order its label runs.
    *
    * ==Out of the ordinary run, by the rule the state tier states==
    *
    * `org.fukuii.chainspec.certification.CertificationCorpora.portedStaticBulk`
    * states which corpora the ordinary run reads: a fork's own new corpus, which
    * is the feedback loop for the machinery that fork adds, and not a re-pinned
    * legacy suite, whose cases are breadth over machinery that is not new. Every
    * group here is that suite re-filled at its label's fork, so every group is
    * read only by a property carrying `org.fukuii.Heavy` -- the `for_cancun`
    * group of 7,040 cases and each earlier label's group of 64 alike, since the
    * rule is about what a corpus is rather than what it costs.
    *
    * **The state tier applies that rule to its bulk, and draws the line
    * differently for the rest**: its per-fork censuses read each label's two
    * stack-overflow files with everything else, and it keeps three `for_cancun`
    * `ported_static` subdirectories in the ordinary run for coverage-matrix rows.
    * This tier applies the rule as written. For the groups of 64, nothing moved
    * out goes uncovered, as below; for `for_cancun`'s group, which the ordinary
    * run never read, whether it covers every rule that group exercises is
    * unmeasured.
    *
    * **Each group of 64 is the suite's two stack-overflow files**, and each of
    * those labels also carries the generated corpus's own stack-overflow files,
    * which the ordinary run still reads. So a regression in that rule still fails
    * a run nobody had to ask for, which is the bar `org.fukuii.evm.fixtures.Heavy`
    * sets for what may carry the tag.
    *
    * ==Derived from the labels, so one declaration moves both==
    *
    * A group is here exactly where its label leaves [[PortedStatic]] out. A label
    * that read its group again would drop the group from this list and move its
    * own pinned counts in the same change.
    */
  val PortedStaticGroups: Vector[BlockchainLabel] =
    Labels.filter(_.excludedGroups.contains(PortedStatic)).map { label =>
      BlockchainLabel(
        label.name + "/" + PortedStatic,
        root => label.directory(root).resolve(PortedStatic),
        Set.empty,
        label.network,
        label.filledBy
      )
    }

  /** One report per group, or nothing where the corpus cannot be located.
    *
    * Apart from [[reports]], which every spec here shares, so that a run asking
    * no question of these groups never assembles them.
    */
  lazy val portedStaticReports: Option[Vector[BlockchainReport]] =
    FixtureCorpus.root.map(root => PortedStaticGroups.map(group => report(root, group)))

  def report(root: Path, label: BlockchainLabel): BlockchainReport =
    val base = label.directory(root)
    val files = FixtureCorpus.jsonFilesUnder(base).filterNot { file =>
      label.excludedGroups.contains(base.relativize(file).getName(0).toString)
    }
    BlockchainReport(label.name, files.length, files.flatMap(file => outcomesIn(base, file, label)))

  /** The cases one file under a label decodes to, for a caller that reads a
    * named file rather than a whole label.
    */
  def casesIn(root: Path, label: BlockchainLabel, relative: String): Either[String, BlockchainFile] =
    val file = label.directory(root).resolve(relative)
    FixtureCorpus.read(file).flatMap(BlockchainFixture.decodeFile(relative, _, label.keeps))

  private def outcomesIn(base: Path, file: Path, label: BlockchainLabel): Vector[BlockchainOutcome] =
    val relative = base.relativize(file).toString
    FixtureCorpus.read(file).flatMap(BlockchainFixture.decodeFile(relative, _, label.keeps)) match
      case Left(error) =>
        Vector(unread(relative, error))
      case Right(decoded) =>
        decoded.undecodable.map((name, reason) => unread(relative + " " + name, reason)) ++
          decoded.fixtures.map { fixture =>
            BlockchainOutcome(
              relative + " " + fixture.name,
              StatedShape.of(fixture),
              BlockchainRunner.run(fixture, FixtureNetworks.named, label.filledBy)
            )
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
