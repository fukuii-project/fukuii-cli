package org.fukuii.consensus.pow.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment, DifficultyBombPause}
import org.fukuii.consensus.pow.EthashEngine

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
  * ==A label names a RULE SET and the height is a free parameter==
  *
  * That is the published corpus's own convention, which [[DifficultyCorpus]]
  * already relies on: its `dfFrontier` and `dfByzantium` tiers both span 100,000
  * to 4,900,000, so one fork's rules are asked at another's heights. A label
  * here says which of this chain's rules are present, and each present rule is
  * then evaluated at the case's own height against its own parameter.
  *
  * **The two halves of that sentence resolve differently, and taking either half
  * for the whole loses cases in every one of the three files.** Which adjustment
  * algorithm is present is the label's answer at every height -- `ETC_Homestead`
  * carries the graduated rule at block 150,000, which is below the 1,150,000
  * this chain activated it at -- while the exponential term's two modifiers
  * carry their real heights and are inert below them. So a harness resolving the
  * algorithm against its own activation loses cases, and so does one declaring
  * the term per label instead of evaluating it at the height; neither failure is
  * confined to the file its half is about.
  *
  * ==What a label selects, and where each height comes from==
  *
  * Two rules move the exponential term on this chain and neither is a delay:
  *
  *   - **ECIP-1010** freezes the term's reference point at block 3,000,000 and
  *     resumes at 5,000,000 with the span between them subtracted. The pair is
  *     the proposal's own `pause_block` and `cont_block` at
  *     `ethereumclassic/ECIPs` @ `f398567f4`, and is stated independently by
  *     `openethereum/parity-ethereum` @ `55c90d401`, whose
  *     `ethcore/res/ethereum/classic.json` carries `ecip1010PauseTransition`
  *     `0x2dc6c0` and `ecip1010ContinueTransition` `0x4c4b40`, and by
  *     `ethereumclassic/core-geth` @ `4185df450`, whose `ClassicChainConfig`
  *     states the same window as `ECIP1010PauseBlock` 3,000,000 with
  *     `ECIP1010Length` 2,000,000.
  *   - **ECIP-1041** removes the term from block 5,900,000 --
  *     `ethereumclassic/ECIPs` @ `8dda72c24`, which names the height in its own
  *     abstract; `bombDefuseTransition` `0x5a06e0` in the same parity chain
  *     specification, and `DisposalBlock` in the same core-geth configuration.
  *
  * **ECIP-1041 sits on top of ECIP-1010 rather than replacing it**, so nine of
  * the twelve labels carry the pause and seven of those nine carry the removal
  * as well. The removing labels still answer a term below 5,900,000, because a
  * removal has an activation height like any other rule.
  *
  * ==Neither height is read back from a schedule==
  *
  * Stated here as literals for the reason [[DifficultyCorpus]] gives for its
  * own: a harness asking a schedule what a fork resolves to, and then checking
  * the corpus against that answer, is true of any schedule whatsoever. This
  * build's Ethereum Classic schedule stops below the first of these heights in
  * any case, so there is nothing there to close the loop with yet.
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
    *
    * **The two proposals this tier does turn on reach the engine through the
    * rules rather than through the engine's own parameters**, so an engine built
    * with nothing still answers every case here -- which is what makes the table
    * below the whole of what a divergence can be about.
    */
  private val engine: EthashEngine = EthashEngine()

  /** The window ECIP-1010 holds the exponential term's reference point over. */
  private val pause: DifficultyBombPause =
    DifficultyBombPause(pausedFrom = BigInt(3000000), continuesFrom = BigInt(5000000))

  /** The first height ECIP-1041 states no exponential term at. */
  private val removedFrom: BigInt = BigInt(5900000)

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
    *
    * **Gotham repeats Die Hard rather than adding to it.** It settles an
    * emission and nothing about difficulty, so its expectations must equal Die
    * Hard's; the corpus states them separately so that the equality is asserted
    * rather than assumed.
    */
  private[certification] def rulesFor(fork: String): Option[ConsensusRules] =
    val base = ConsensusRules.Unrewarded
    fork match
      case "ETC_Frontier"                                 => Some(base)
      case "ETC_Homestead" | "ETC_GasReprice"             => Some(graduated(base))
      case "ETC_DieHard" | "ETC_Gotham"                   => Some(paused(graduated(base)))
      case "ETC_DefuseDifficultyBomb"                     => Some(removed(paused(graduated(base))))
      case "ETC_Atlantis" | "ETC_Agharta" | "ETC_Phoenix" => Some(removed(paused(ommerAware(base))))
      case "ETC_Magneto" | "ETC_Mystique" | "ETC_Spiral"  => Some(removed(paused(ommerAware(base))))
      case _                                              => None

  private def graduated(base: ConsensusRules): ConsensusRules =
    base.copy(difficultyAdjustment = DifficultyAdjustment.Eip2)

  private def ommerAware(base: ConsensusRules): ConsensusRules =
    base.copy(difficultyAdjustment = DifficultyAdjustment.Eip100)

  private def paused(base: ConsensusRules): ConsensusRules =
    base.copy(difficultyBombPause = Some(pause))

  private def removed(base: ConsensusRules): ConsensusRules =
    base.copy(difficultyBombRemovedFrom = Some(removedFrom))

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

  private def timestampOf(fixture: DifficultyFixture): UInt64 =
    UInt64
      .fromBigInt(fixture.timestamp)
      .getOrElse(throw new IllegalStateException("a timestamp wider than a header can carry: " + fixture.timestamp))

  /** Every case the corpus states, as the runner sees them before any verdict.
    *
    * Read by the negative controls, which need the same inputs the run used and
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
