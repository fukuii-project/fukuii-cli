package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** ECIP-1099's epoch schedule, which no published corpus states.
  *
  * ==What these expectations rest on, and where that is weaker==
  *
  * The corpus names ECIP-1099 and the ethash specification as its oracle, with
  * the algorithm cross-checked against `ethereumclassic/core-geth`
  * v1.12.21-unstable @ `4185df450`. That is a derivation from the same proposal
  * this build was written from, checked against one client -- weaker than the
  * emission tiers beside it, which are confirmed against the chain itself. **The
  * wrong-seed control is what keeps it from being one computation compared with
  * a copy of itself**: the corpus states the value a plausible misreading
  * produces, so agreement is measured against a stated alternative.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled, for the reason
  * [[DifficultyCertificationSpec]] states.
  */
class EtchashEpochCertificationSpec extends AnyFlatSpec:

  /** Heights the schedule is pinned at. */
  private val Heights: Int = 16

  /** Of those, the ones at or above the activation.
    *
    * The other ten precede it, where an engine that never adopts the proposal
    * answers identically -- so these six are the whole of what separates the
    * two engines, and the control below must refuse exactly them.
    */
  private val PastActivation: Int = 6

  /** Figures pinned per height: the length, the epoch, its first block, the
    * seed, and the two sizes.
    */
  private val Figures: Int = 16 * 6

  /** Epochs the seed-continuity identity is stated at. */
  private val ContinuityVectors: Int = 5

  /** Epochs the wrong seed is published for. */
  private val WrongSeedVectors: Int = 2

  private val report: CorpusReport =
    EtchashEpochCorpus.report.getOrElse(
      fail(
        "the network corpus was not found: set " + NetworkFixtureCorpus.RootVariable + " or write " +
          NetworkFixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  private val withoutProposal: CorpusReport =
    EtchashEpochCorpus.withoutActivation.getOrElse(fail("the network corpus was not found"))

  "the epoch schedule" should "yield every height the corpus states" in
    assert(
      report.casesFound == Heights,
      "found " + report.casesFound.toString + " rather than " + Heights.toString + ": " + report.describe
    )

  it should "skip none of them" in
    assert(
      report.skipped.isEmpty,
      "an epoch vector states its figures unconditionally, so a skip is a reader fault: " + report.describe
    )

  it should "agree with every figure at every height" in
    assert(report.diverged.isEmpty, report.describe)

  it should "pin the stated number of figures" in
    assert(
      EtchashEpochCorpus.figuresAsserted == Figures,
      "pinned " + EtchashEpochCorpus.figuresAsserted.toString + " figures rather than " + Figures.toString
    )

  "the seed chain" should "continue across the activation rather than restart" in
    assert(
      EtchashEpochCorpus.continuity.forall(vector =>
        EtchashEpochCorpus.seedAtPostForkEpoch(vector.postForkEpoch) == vector.seedHash
      ),
      "a post-fork epoch must take the seed its own start block's iteration count gives"
    )

  it should "give a post-fork epoch the seed of twice its number under the original length" in
    assert(
      EtchashEpochCorpus.continuity.forall(vector =>
        EtchashEpochCorpus.seedAtPostForkEpoch(vector.postForkEpoch) ==
          EtchashEpochCorpus.seedAtLegacyEpoch(vector.equivalentLegacyEpoch)
      ),
      "the identity that makes the seed chain continuous is what a build using one divisor throughout breaks"
    )

  it should "be stated at every epoch the corpus pins it at" in
    assert(
      EtchashEpochCorpus.continuity.length == ContinuityVectors,
      "the corpus states the identity at " + EtchashEpochCorpus.continuity.length.toString + " epochs rather than " +
        ContinuityVectors.toString
    )

  "the seed a wrong divisor gives" should "be reachable, so the control is not vacuous" in
    assert(
      EtchashEpochCorpus.wrongSeeds.forall(vector =>
        EtchashEpochCorpus.seedAtLegacyEpoch(vector.postForkEpoch) == vector.wrongSeedHash
      ),
      "the corpus's stated wrong seed must be the value this build produces when handed the original length, " +
        "or the control is comparing against a value nothing could compute"
    )

  it should "never be what this build answers" in
    assert(
      EtchashEpochCorpus.wrongSeeds.forall(vector =>
        EtchashEpochCorpus.seedAtPostForkEpoch(vector.postForkEpoch) == vector.correctSeedHash &&
          EtchashEpochCorpus.seedAtPostForkEpoch(vector.postForkEpoch) != vector.wrongSeedHash
      ),
      "dividing by the length in force yields a seed already used six million blocks earlier, so this build " +
        "would generate a real dataset for the wrong epoch rather than fail"
    )

  it should "be published at every epoch the corpus pins it at" in
    assert(
      EtchashEpochCorpus.wrongSeeds.length == WrongSeedVectors,
      "the corpus publishes " + EtchashEpochCorpus.wrongSeeds.length.toString + " wrong seeds rather than " +
        WrongSeedVectors.toString
    )

  "an engine that never doubles its epoch length" should "answer correctly below the activation" in
    assert(
      withoutProposal.agreed.length == Heights - PastActivation,
      "an engine without the proposal agreed at " + withoutProposal.agreed.length.toString +
        " heights rather than " + (Heights - PastActivation).toString + ": " + withoutProposal.describe
    )

  it should "be refused at every height from the activation onward" in
    assert(
      withoutProposal.diverged.length == PastActivation,
      "the proposal is unpinned at " + (PastActivation - withoutProposal.diverged.length).toString +
        " heights, so this tier does not separate a build carrying it from one that does not: " +
        withoutProposal.describe
    )
