package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment}
import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus, SkipReason, Verdict}

/** The difficulty rule against Ethereum Classic's own schedule at that chain's
  * real activation heights.
  *
  * ==What these expectations rest on==
  *
  * The three files carry an oracle apiece. `block_interval_adjustment.json` and
  * `uncle_adjustment.json` name `core-geth v1.12.21-unstable @ 4185df450`, run
  * through that client's own difficulty machinery and then independently
  * re-derived from EIP-2 and EIP-100 rather than from any client.
  * `bomb_pause_and_removal.json` names two implementations read line by line --
  * the same core-geth, and a Nethermind Ethereum Classic overlay whose
  * `DifficultyBombCalculator.cs` expresses the pause with no reference point in
  * it at all -- agreeing at every one of 612 probed heights. Agreement across a
  * representational difference is the part that makes it a cross-check.
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
  * than a smaller green.
  */
class ClassicDifficultyCertificationSpec extends AnyFlatSpec:

  /** The three files the tier states its cases in. */
  private val Files: Int = 3

  /** Cases across those files: eight intervals and eight ommer pairings at
    * twelve labels, and sixteen bomb heights at the same twelve.
    */
  private val Cases: Int = 384

  /** Cases this build answers today. */
  private val Certified: Int = 249

  /** Cases whose answer needs ECIP-1010 or ECIP-1041.
    *
    * All of them are in the bomb file: the other two hold every case at block
    * 150,000, below where the exponential term begins, so no label defers a
    * single case there.
    */
  private val Deferred: Int = 135

  /** What each deferring label defers, which is the same fifteen of its sixteen
    * bomb-file cases -- the sixteenth sits below the term and is certified.
    */
  private val DeferredPerLabel: Int = 15

  private val DeferringLabels: Set[String] =
    Set(
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

  private val Labels: Set[String] =
    DeferringLabels ++ Set("ETC_Frontier", "ETC_Homestead", "ETC_GasReprice")

  private val report: CorpusReport =
    ClassicDifficultyCorpus.report.getOrElse(
      fail(
        "the network corpus was not found: set " + NetworkFixtureCorpus.RootVariable + " or write " +
          NetworkFixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** Every label answered by one algorithm, which no schedule states.
    *
    * The control this suite needs: the runner is handed a table that is wrong in
    * a way the corpus must notice, and a run that still agreed everywhere would
    * show the cases cannot separate one algorithm from another.
    */
  private def underOneAlgorithm(adjustment: DifficultyAdjustment): CorpusReport =
    val flattened: String => Option[ConsensusRules] =
      fork =>
        ClassicDifficultyCorpus
          .rulesFor(fork)
          .map(_.copy(difficultyAdjustment = adjustment))
    CorpusReport(
      "control",
      Files,
      ClassicDifficultyCorpus.fixtures
        .getOrElse(Vector.empty)
        .map(ClassicDifficultyCorpus.outcomeOf(_, flattened))
    )

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

  it should "defer the rest to the two proposals that settle them" in
    assert(
      report.skipped.length == Deferred,
      "deferred " + report.skipped.length.toString + " rather than " + Deferred.toString + ": " + report.describe
    )

  it should "defer nothing for a reason other than an unbuilt rule" in
    assert(
      report.skipped.forall(_.verdict match
        case Verdict.Skipped(SkipReason.RuleNotBuilt(_)) => true
        case _                                           => false),
      "a deferral for any other reason is a reader fault rather than a gap in this build: " + report.describe
    )

  "the deferral" should "fall only on labels selecting a proposal this build lacks" in
    assert(
      ClassicDifficultyCorpus.deferredCases.keySet == DeferringLabels,
      "deferred under " + ClassicDifficultyCorpus.deferredCases.keySet.toSeq.sorted.mkString(",") +
        " rather than under " + DeferringLabels.toSeq.sorted.mkString(",")
    )

  it should "cost each of those labels the same fifteen cases" in
    assert(
      ClassicDifficultyCorpus.deferredCases.values.forall(_ == DeferredPerLabel),
      "per-label deferrals were " + ClassicDifficultyCorpus.deferredCases.toSeq.sorted.mkString(",")
    )

  "the harness" should "state rules for every label the tier names and for no other" in
    assert(
      Labels.forall(ClassicDifficultyCorpus.rulesFor(_).isDefined) &&
        ClassicDifficultyCorpus.rulesFor("ETC_Thanos").isEmpty,
      "a label the corpus names and the harness cannot resolve is counted as a divergence per case, not as a gap"
    )

  it should "refuse a table putting the binary rule at every label" in
    assert(
      underOneAlgorithm(DifficultyAdjustment.Original).diverged.length == 101,
      "the corpus cannot separate the binary rule from the graduated one: " +
        underOneAlgorithm(DifficultyAdjustment.Original).describe
    )

  it should "refuse a table putting the graduated rule at every label" in
    assert(
      underOneAlgorithm(DifficultyAdjustment.Eip2).diverged.length == 43,
      "the corpus cannot tell the graduated rule from the two it sits between: " +
        underOneAlgorithm(DifficultyAdjustment.Eip2).describe
    )

  it should "refuse a table putting the ommer-aware rule at every label" in
    assert(
      underOneAlgorithm(DifficultyAdjustment.Eip100).diverged.length == 86,
      "the corpus cannot separate the ommer-aware numerator from its predecessors: " +
        underOneAlgorithm(DifficultyAdjustment.Eip100).describe
    )
