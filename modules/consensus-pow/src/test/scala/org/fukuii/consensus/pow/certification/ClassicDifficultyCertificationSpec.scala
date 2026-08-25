package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment, DifficultyBombPause}
import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** The difficulty rule against Ethereum Classic's own schedule at that chain's
  * real activation heights.
  *
  * ==What these expectations rest on==
  *
  * The three files carry an oracle apiece. `block_interval_adjustment.json` and
  * `uncle_adjustment.json` name `core-geth v1.12.21-unstable @ 4185df450`, run
  * through that client's own difficulty machinery and then independently
  * re-derived from EIP-2 and EIP-100 rather than from any client.
  * `bomb_pause_and_removal.json` names three implementations sharing no commit
  * -- `core-geth` root `5db3335dc` in Go, `openethereum/parity-ethereum` root
  * `f7b618cec` in Rust, `besu-eth/besu-etc` root `7dfc2e408` in Java -- each
  * stating ECIP-1010 in a different shape and agreeing on every case here.
  * Agreement across a representational difference is the part that makes it a
  * cross-check rather than three readings of one computation.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled, for the reason
  * [[DifficultyCertificationSpec]] states: a cancelled test is counted by
  * nothing, so a build whose corpus vanished reports the same executed total as
  * one that ran it.
  *
  * ==The figures are stated, not derived from the run==
  *
  * Every count below is a literal, so a corpus that shrank is a failure rather
  * than a smaller green. **The control counts are literals for a second reason
  * as well**: a control asserted only to be non-zero passes on a defect it never
  * reached, so each states how many cases the seeded rule set must lose.
  */
class ClassicDifficultyCertificationSpec extends AnyFlatSpec:

  /** The three files the tier states its cases in. */
  private val Files: Int = 3

  /** Cases across those files: eight intervals and eight ommer pairings at
    * twelve labels, and sixteen bomb heights at the same twelve.
    */
  private val Cases: Int = 384

  /** Cases this build answers, which is now every one the tier states. */
  private val Certified: Int = 384

  private val Labels: Set[String] =
    Set(
      "ETC_Frontier",
      "ETC_Homestead",
      "ETC_GasReprice",
      "ETC_DieHard",
      "ETC_Gotham",
      "ETC_DefuseDifficultyBomb",
      "ETC_Atlantis",
      "ETC_Agharta",
      "ETC_Phoenix",
      "ETC_Magneto",
      "ETC_Mystique",
      "ETC_Spiral"
    )

  /** The window this chain pauses the exponential term over, restated here so
    * that a control can put it at labels the chain does not put it at.
    */
  private val Pause: DifficultyBombPause =
    DifficultyBombPause(pausedFrom = BigInt(3000000), continuesFrom = BigInt(5000000))

  /** The height this chain removes the term at, restated for the same reason. */
  private val RemovedFrom: BigInt = BigInt(5900000)

  /** How many blocks one doubling of the term lasts, which is the unit a window
    * of the wrong length is wrong by.
    */
  private val ExponentialPeriod: BigInt = BigInt(100000)

  private val report: CorpusReport =
    ClassicDifficultyCorpus.report.getOrElse(
      fail(
        "the network corpus was not found: set " + NetworkFixtureCorpus.RootVariable + " or write " +
          NetworkFixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** The same run, over a table this harness has been made wrong in one stated
    * way.
    *
    * The control every count below needs: the runner is handed a rule set the
    * corpus must notice is not this chain's, and a run that still agreed
    * everywhere would show the cases cannot separate the seeded rule from the
    * real one.
    */
  private def under(seeded: ConsensusRules => ConsensusRules): CorpusReport =
    CorpusReport(
      "control",
      Files,
      ClassicDifficultyCorpus.fixtures
        .getOrElse(Vector.empty)
        .map(ClassicDifficultyCorpus.outcomeOf(_, ClassicDifficultyCorpus.rulesFor(_).map(seeded)))
    )

  private def divergences(seeded: ConsensusRules => ConsensusRules): Int = under(seeded).diverged.length

  private def binaryRule(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyAdjustment = DifficultyAdjustment.Original)

  private def graduatedRule(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyAdjustment = DifficultyAdjustment.Eip2)

  private def ommerAwareRule(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyAdjustment = DifficultyAdjustment.Eip100)

  private def unpaused(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombPause = None)

  private def pausedEverywhere(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombPause = Some(Pause))

  private def pauseAsDelay(rules: ConsensusRules): ConsensusRules =
    rules.difficultyBombPause.fold(rules)(window =>
      rules.copy(
        difficultyBombPause = None,
        difficultyBombDelay = window.continuesFrom - window.pausedFrom
      )
    )

  private def resumingOnePeriodLate(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombPause =
      rules.difficultyBombPause.map(window => window.copy(continuesFrom = window.continuesFrom + ExponentialPeriod))
    )

  private def neverRemoved(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombRemovedFrom = None)

  private def removedEverywhere(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombRemovedFrom = Some(RemovedFrom))

  private def removedOneBlockLate(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombRemovedFrom = rules.difficultyBombRemovedFrom.map(_ + 1))

  "this chain's difficulty tier" should "be read in full" in
    assert(
      report.filesRead == Files,
      "read " + report.filesRead.toString + " files rather than " + Files.toString + ": " + report.describe
    )

  it should "yield every case the tier states" in
    assert(
      report.casesFound == Cases,
      "found " + report.casesFound.toString + " cases rather than " + Cases.toString + ": " + report.describe
    )

  it should "agree with every case it answers" in
    assert(report.diverged.isEmpty, report.describe)

  it should "answer the stated number of them" in
    assert(
      report.agreed.length == Certified,
      "certified " + report.agreed.length.toString + " rather than " + Certified.toString + ": " + report.describe
    )

  it should "skip nothing at all" in
    assert(
      report.skipped.isEmpty,
      "both proposals this tier turns on are built, so a skip is a reader fault rather than a gap in this " +
        "build: " + report.describe
    )

  "the harness" should "state rules for every label the tier names and for no other" in
    assert(
      Labels.forall(ClassicDifficultyCorpus.rulesFor(_).isDefined) &&
        ClassicDifficultyCorpus.rulesFor("ETC_Thanos").isEmpty,
      "a label the corpus names and the harness cannot resolve is counted as a divergence per case, not as a gap"
    )

  it should "leave every case answered when nothing is seeded" in
    assert(
      divergences(identity) == 0,
      "the seeding path must not perturb the run, or every count below measures the seeding: " +
        under(identity).describe
    )

  it should "refuse a table putting the binary rule at every label" in
    assert(
      divergences(binaryRule) == 191,
      "the corpus cannot separate the binary rule from the graduated one: " + under(binaryRule).describe
    )

  it should "refuse a table putting the graduated rule at every label" in
    assert(
      divergences(graduatedRule) == 133,
      "the corpus cannot tell the graduated rule from the two it sits between: " + under(graduatedRule).describe
    )

  it should "refuse a table putting the ommer-aware rule at every label" in
    assert(
      divergences(ommerAwareRule) == 131,
      "the corpus cannot separate the ommer-aware numerator from its predecessors: " + under(ommerAwareRule).describe
    )

  "a build without ECIP-1010" should "lose the cases where the term is frozen or resumed" in
    assert(
      divergences(unpaused) == 71,
      "a term left growing through the window is what this chain does not have, and the tier must say so: " +
        under(unpaused).describe
    )

  it should "lose them with the pause spelled as a delay of the window's own span" in
    assert(
      divergences(pauseAsDelay) == 63,
      "no delay makes the term constant across a window, so a build passing this would teach that one does: " +
        under(pauseAsDelay).describe
    )

  it should "lose cases when the window is given to labels that predate it" in
    assert(
      divergences(pausedEverywhere) == 33,
      "which labels carry the rule is asserted, not only that some label does: " + under(pausedEverywhere).describe
    )

  it should "lose cases when the window resumes one period late" in
    assert(
      divergences(resumingOnePeriodLate) == 35,
      "the height the term resumes from is pinned, so a window of the wrong length is a divergence: " +
        under(resumingOnePeriodLate).describe
    )

  "a build without ECIP-1041" should "lose the cases at and above the removal" in
    assert(
      divergences(neverRemoved) == 28,
      "a term this chain deleted is one every later block would otherwise be mined against: " +
        under(neverRemoved).describe
    )

  it should "lose the boundary case when the removal is one block late" in
    assert(
      divergences(removedOneBlockLate) == 7,
      "the boundary is asserted at the removal's own height rather than somewhere below it: " +
        under(removedOneBlockLate).describe
    )

  it should "lose cases when the removal is given to labels that predate it" in
    assert(
      divergences(removedEverywhere) == 20,
      "the labels below the removal still answer a term, which is what tells a removal from a large delay: " +
        under(removedEverywhere).describe
    )
