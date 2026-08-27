package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment}
import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus, SkipReason, Verdict}

/** The difficulty rule against the published cases that sit near the floor.
  *
  * ==What this tier is for, which is not its size==
  *
  * It answers 108 cases where [[DifficultyCertificationSpec]] answers 18,598,
  * and it is the only one of the two that can separate taking the floor over
  * the adjustment from taking it over the sum. The other tier's smallest parent
  * difficulty is some hundreds of millions of times the floor, so every case in
  * it agrees under either order; a corpus that could not have disagreed is not
  * evidence that it agrees. The case below counting cases below the floor
  * asserts the size of the region this one does reach, so that the claim is
  * checked rather than described.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled, for the reason [[DifficultyCertificationSpec]]
  * states: a cancelled test is counted by nothing, so a build whose corpus
  * vanished reports the same executed total as one that ran it.
  *
  * ==The figures are stated, not derived from the run==
  *
  * Every count below is a literal read off the corpus, so a reader can see the
  * denominator without running anything and a corpus that shrank is a failure
  * rather than a smaller green. **The control counts are literals for a second
  * reason**: a control asserted only to be non-zero passes on a defect it never
  * reached, so each states how many cases the seeded rule set must lose.
  */
class BasicTestsDifficultyCertificationSpec extends AnyFlatSpec:

  /** Files read: one of the five the directory holds. `BasicTestsDifficultyCorpus`
    * states what the other four were measured to contain and why each is left
    * out.
    */
  private val Files: Int = 1

  /** Cases in that file. */
  private val Cases: Int = 120

  /** Cases whose block does not follow its parent in time, which the rule is not
    * stated over and refuses.
    */
  private val Refused: Int = 12

  /** Cases this build answers. */
  private val Certified: Int = 108

  /** Cases stating a parent difficulty below the floor, which is the region the
    * other published tier holds none of.
    *
    * **An input-side count and not a divergence count.** Nine of these are also
    * refused for their timestamp, so the tier evaluates 81 of them -- which is
    * the figure that moves when the floor's placement moves, and the one
    * `EthashDifficultySpec` names.
    */
  private val SubFloor: Int = 90

  /** The difficulty no adjustment may take a block below, restated here so the
    * region above can be counted without asking the engine what it thinks.
    */
  private val MinimumDifficulty: BigInt = BigInt(131072)

  /** Every difficulty file the directory holds, so that one arriving upstream is
    * a failure here rather than a file quietly left unread.
    */
  private val Present: Vector[String] =
    Vector(
      "difficulty.json",
      "difficultyCustomHomestead.json",
      "difficultyCustomMainNetwork.json",
      "difficultyMainNetwork.json",
      "difficultyRopsten.json"
    )

  private val report: CorpusReport =
    BasicTestsDifficultyCorpus.report.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** The same run, over a table this harness has been made wrong in one stated
    * way.
    *
    * The control every count below needs: the runner is handed a rule set the
    * corpus must notice is not the one its cases were filled under, and a run
    * that still agreed everywhere would show the cases cannot separate the
    * seeded rule from the real one.
    */
  private def under(seeded: ConsensusRules => ConsensusRules): CorpusReport =
    CorpusReport(
      "control",
      Files,
      BasicTestsDifficultyCorpus.fixtures
        .getOrElse(Vector.empty)
        .map(BasicTestsDifficultyCorpus.outcomeOf(_, BasicTestsDifficultyCorpus.rulesFor(_).map(seeded)))
    )

  private def divergences(seeded: ConsensusRules => ConsensusRules): Int = under(seeded).diverged.length

  private def binaryRule(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyAdjustment = DifficultyAdjustment.Original)

  private def ommerAwareRule(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyAdjustment = DifficultyAdjustment.Eip100)

  private def termRemovedEverywhere(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombRemovedFrom = Some(BigInt(0)))

  "the near-floor difficulty tier" should "be read in full" in
    assert(
      report.filesRead == Files,
      "read " + report.filesRead.toString + " files rather than " + Files.toString + ": " + report.describe
    )

  it should "yield every case the file states" in
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

  it should "decline the cases whose block does not follow its parent, and no others" in
    assert(
      report.skipped.length == Refused &&
        report.skipped.forall(_.verdict match
          case Verdict.Skipped(SkipReason.InputRefused(_)) => true
          case _                                           => false),
      "a skip under any other reason is a reader fault rather than an input the rule is not stated over: " +
        report.describe
    )

  /** The claim the tier exists to make good, counted from the cases themselves.
    *
    * Read off the fixtures rather than off a verdict, because a run that agreed
    * everywhere would satisfy every assertion above while stating nothing about
    * whether the floor was reachable at all.
    */
  it should "state the cases the larger tier cannot reach" in
    assert(
      BasicTestsDifficultyCorpus.fixtures
        .getOrElse(Vector.empty)
        .count(_.parentDifficulty < MinimumDifficulty) == SubFloor,
      "the region where an adjusted difficulty falls below the floor is what separates taking the floor over " +
        "the sum from taking it over the adjustment, and a tier not holding it certifies neither"
    )

  "the harness" should "read every difficulty file the directory holds or state why not" in
    assert(
      BasicTestsDifficultyCorpus.difficultyFilesPresent.contains(Present),
      "a difficulty file arriving upstream, or one being renamed, must be a failure here rather than a file " +
        "silently left unread: found " + BasicTestsDifficultyCorpus.difficultyFilesPresent.toString
    )

  it should "state rules for every fork it wires and for no other" in
    assert(
      BasicTestsDifficultyCorpus.Wired.values.forall(BasicTestsDifficultyCorpus.rulesFor(_).isDefined) &&
        BasicTestsDifficultyCorpus.rulesFor("Paris").isEmpty,
      "a fork the harness wires and cannot resolve is counted as a divergence per case, not as a gap"
    )

  it should "leave every case answered when nothing is seeded" in
    assert(
      divergences(identity) == 0,
      "the seeding path must not perturb the run, or every count below measures the seeding: " +
        under(identity).describe
    )

  it should "refuse the binary rule in place of the graduated one" in
    assert(
      divergences(binaryRule) == 18,
      "the cases cannot separate the binary rule from the graduated one: " + under(binaryRule).describe
    )

  it should "refuse the ommer-aware rule in place of the graduated one" in
    assert(
      divergences(ommerAwareRule) == 6,
      "the cases cannot separate the ommer-aware numerator from the graduated one: " + under(ommerAwareRule).describe
    )

  /** The control that pins what makes this tier discriminating.
    *
    * The two orders coincide wherever the exponential term is zero, so a tier
    * whose cases all sat below the term's first period would separate them on
    * nothing however far below the floor they fell. Removing the term must
    * therefore cost cases here, and the count is how many carry a term at all
    * while below the floor.
    */
  "a build with no exponential term" should "lose the cases that carry one" in
    assert(
      divergences(termRemovedEverywhere) == 27,
      "a tier whose cases carry no term cannot show where the floor is taken, whatever else it certifies: " +
        under(termRemovedEverywhere).describe
    )
