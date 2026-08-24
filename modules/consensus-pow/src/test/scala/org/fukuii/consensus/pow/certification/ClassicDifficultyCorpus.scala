package org.fukuii.consensus.pow.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment}
import org.fukuii.consensus.pow.ProofOfWorkEngine

/** The difficulty rule against Ethereum Classic's own schedule, at that chain's
  * real activation heights.
  *
  * ==Why this is a second harness and not more rows in the published one==
  *
  * [[DifficultyCorpus]] resolves the fork names the published tier files its
  * cases under, and those name Ethereum's schedule. These name a different
  * chain's, and two of this one's labels select proposals that appear nowhere in
  * the published tier. Folding them into one table would leave a reader unable
  * to tell which chain a divergence was about.
  *
  * ==What a label selects, and why some of them cannot be answered yet==
  *
  * A label says which of this chain's difficulty rules are present at a case's
  * height; each present rule is then evaluated at that height with its real
  * parameter. Two of those rules are not built here:
  *
  *   - **ECIP-1010** freezes the exponential term's reference point at block
  *     3,000,000 and holds it for 2,000,000 blocks, then resumes with that span
  *     subtracted.
  *   - **ECIP-1041** removes the exponential term outright from block 5,900,000.
  *
  * Both heights are `ECIP1010PauseBlock`, `ECIP1010Length` and `DisposalBlock`
  * in `ethereumclassic/core-geth` @ `4185df450`'s `ClassicChainConfig`, which is
  * also where every adjustment-algorithm height below is read from.
  *
  * ==The cases those two rules do not reach are still certified, and the test is
  * asked of the engine rather than stated==
  *
  * Both proposals only ever make the exponential term SMALLER -- one moves its
  * reference point backwards, the other deletes it -- so wherever the plain term
  * is already nothing, a build carrying neither answers exactly what a build
  * carrying both would. That is a proof rather than a sampling argument, and it
  * is what lets the majority of these cases be certified today.
  *
  * **[[carriesExponentialTerm]] asks the engine instead of restating the height
  * the term begins at.** A constant here would be a second transcription of one
  * the engine already owns, and the two would drift apart silently; a harness
  * that asks cannot be wrong about it in a direction the engine is not.
  */
object ClassicDifficultyCorpus:

  /** Where the tier sits under the network corpus root. */
  val Corpus: String = "fukuii-tests ethereumclassic/mainnet difficulty"

  private def directory(under: Path): Path =
    NetworkFixtureCorpus.classicMainnet(under).resolve("difficulty")

  /** The engine under certification.
    *
    * No era ladder and no epoch activation, because difficulty reads neither.
    * An engine carrying them would answer identically at every case here, and
    * choosing the one that cannot is what keeps that independence testable.
    */
  private val engine: ProofOfWorkEngine = ProofOfWorkEngine()

  /** What this harness believes each of this chain's labels settles about
    * difficulty.
    *
    * The heights each name activates at are
    * `ethereumclassic/core-geth` @ `4185df450`'s `ClassicChainConfig`:
    * `EIP2FBlock` at 1,150,000 puts the graduated term in from Homestead,
    * `EIP150Block` at 2,500,000 changes gas and no difficulty rule, and
    * `EIP100FBlock` at 8,772,000 puts the ommer-aware numerator in from
    * Atlantis. Everything from Atlantis onward inherits that numerator
    * unchanged, so the labels above it repeat it rather than varying it.
    *
    * **No label carries a bomb delay.** This chain answered the exponential term
    * with ECIP-1010 and then ECIP-1041 and never adopted a delay, so a delay
    * here would be a rule from the other family's schedule wearing this one's
    * name.
    */
  private[certification] def rulesFor(fork: String): Option[ConsensusRules] =
    val base = ConsensusRules.Unrewarded
    fork match
      case "ETC_Frontier"                                            => Some(base)
      case "ETC_Homestead" | "ETC_GasReprice"                        => Some(graduated(base))
      case "ETC_DieHard" | "ETC_Gotham" | "ETC_DefuseDifficultyBomb" => Some(graduated(base))
      case "ETC_Atlantis" | "ETC_Agharta" | "ETC_Phoenix"            => Some(ommerAware(base))
      case "ETC_Magneto" | "ETC_Mystique" | "ETC_Spiral"             => Some(ommerAware(base))
      case _                                                         => None

  private def graduated(base: ConsensusRules): ConsensusRules =
    base.copy(difficultyAdjustment = DifficultyAdjustment.Eip2)

  private def ommerAware(base: ConsensusRules): ConsensusRules =
    base.copy(difficultyAdjustment = DifficultyAdjustment.Eip100)

  /** Which unbuilt proposals a label selects, and nothing where it selects none.
    *
    * Gotham is here because it inherits ECIP-1010 from Die Hard, not because it
    * changes anything about difficulty itself -- it settles an emission. Its
    * expectations must therefore equal Die Hard's, and the corpus states them
    * separately so that equality is asserted rather than assumed.
    */
  private[certification] def deferred(fork: String): Option[String] =
    fork match
      case "ETC_Frontier" | "ETC_Homestead" | "ETC_GasReprice" => None
      case "ETC_DieHard" | "ETC_Gotham"                        => Some("ECIP-1010")
      case _                                                   => Some("ECIP-1010 and ECIP-1041")

  /** Every report, or nothing at all when the corpus cannot be located. */
  lazy val report: Option[CorpusReport] = NetworkFixtureCorpus.root.map(root => assemble(directory(root)))

  private def assemble(under: Path): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(under)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(DifficultyFixture.decodeFile(file.getFileName.toString, _)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(fixtures) => fixtures.map(outcomeOf(_, rulesFor))
    }
    CorpusReport(Corpus, files.length, outcomes)

  /** What running one case established.
    *
    * @param resolve
    *   how a label becomes a rule set. A parameter so that a negative control
    *   can supply a deliberately wrong table and watch the same runner refuse
    *   it: a harness whose only ever input is the answer it expects has no
    *   reachable failing state, and reports agreement it never tested for.
    */
  private[certification] def outcomeOf(
      fixture: DifficultyFixture,
      resolve: String => Option[ConsensusRules]
  ): CaseOutcome =
    val verdict =
      try
        resolve(fixture.fork) match
          case None        => Verdict.Diverged(Vector("no rules are stated for fork " + fixture.fork))
          case Some(rules) =>
            deferred(fixture.fork).filter(_ => carriesExponentialTerm(fixture, rules)) match
              case Some(proposals) =>
                Verdict.Skipped(SkipReason.RuleNotBuilt(proposals + " at block " + fixture.blockNumber.toString))
              case None =>
                val answered =
                  engine.difficulty(
                    rules,
                    DifficultyCorpus.parentOf(fixture),
                    fixture.parentHasOmmers,
                    timestampOf(fixture)
                  )
                if answered.toBigInt == fixture.expected then Verdict.Agreed
                else
                  Verdict.Diverged(
                    Vector("answered " + answered.toBigInt.toString + " rather than " + fixture.expected.toString)
                  )
      catch
        case NonFatal(cause) => Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome(fixture.name, verdict)

  /** Whether the exponential term contributes anything to this case's answer.
    *
    * Asked by settling the same parent twice, once at its own height and once at
    * a height below where the term begins. The adjustment reads a difficulty and
    * two timestamps and never the number, so the whole of any difference between
    * the two answers is the term -- which makes equality the statement that
    * there is no term here to be wrong about.
    */
  private def carriesExponentialTerm(fixture: DifficultyFixture, rules: ConsensusRules): Boolean =
    val parent = DifficultyCorpus.parentOf(fixture)
    val timestamp = timestampOf(fixture)
    val atHeight = engine.difficulty(rules, parent, fixture.parentHasOmmers, timestamp)
    val belowTerm = engine.difficulty(rules, parent.copy(number = UInt64.Zero), fixture.parentHasOmmers, timestamp)
    atHeight != belowTerm

  private def timestampOf(fixture: DifficultyFixture): UInt64 =
    UInt64
      .fromBigInt(fixture.timestamp)
      .getOrElse(throw new IllegalStateException("a timestamp wider than a header can carry: " + fixture.timestamp))

  /** Every case the corpus states, as the runner sees them before any verdict.
    *
    * Read by the negative control, which needs the same inputs the run used and
    * must not re-derive them from a second walk of the tree.
    */
  private[certification] lazy val fixtures: Option[Vector[DifficultyFixture]] =
    NetworkFixtureCorpus.root.map { root =>
      FixtureCorpus.jsonFilesUnder(directory(root)).flatMap { file =>
        FixtureCorpus
          .read(file)
          .flatMap(DifficultyFixture.decodeFile(file.getFileName.toString, _))
          .getOrElse(Vector.empty[DifficultyFixture])
      }
    }

  /** How many cases each label defers, which is what scopes the work building
    * the two proposals would unblock.
    *
    * Counted from the fixtures rather than by reading the skip detail back out
    * of a report, because a count parsed from a message it formatted itself
    * measures the formatting.
    */
  private[certification] def deferredCases: Map[String, Int] =
    fixtures
      .getOrElse(Vector.empty)
      .filter(fixture =>
        rulesFor(fixture.fork).exists(rules =>
          deferred(fixture.fork).isDefined && carriesExponentialTerm(fixture, rules)
        )
      )
      .groupBy(_.fork)
      .view
      .mapValues(_.length)
      .toMap
