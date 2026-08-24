package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** The ECIP-1017 emission against every height this chain's own corpus states.
  *
  * ==What these expectations rest on==
  *
  * The fixture's `_info.oracle` records ECIP-1017 computed independently and
  * cross-checked against `core-geth`'s own reward vectors at eleven heights for
  * the winner's reward and eleven for an ommer's. Its `observedGrounding`
  * records something stronger and rarer: the figures were confirmed against
  * mainnet itself, as miner balance deltas read from archive state across
  * forty-two zero-transaction zero-ommer blocks. An oracle naming the chain is
  * a genuine cross-check in a way an oracle naming only the specification this
  * build was written from would not be.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled, for the reason
  * [[DifficultyCertificationSpec]] states.
  */
class EmissionCertificationSpec extends AnyFlatSpec:

  /** Heights the schedule is pinned at. */
  private val Vectors: Int = 17

  /** Heights inside the first era, where the ladder has not yet reduced
    * anything.
    *
    * The size of the blind spot in the control below rather than a coverage
    * figure: an engine running no ladder at all pays correctly at every one of
    * them, so these three cannot separate the two engines and the other
    * fourteen must.
    */
  private val WithinFirstEra: Int = 3

  /** Figures pinned across the tier, which a case count alone cannot show.
    *
    * Sixteen heights pin twenty-three figures each. The first block pins six:
    * an ommer more than one block behind it would sit below genesis, so the
    * distances that cannot be realized as a block are not asserted there, and
    * every one of them is pinned at another height of the same era.
    */
  private val Figures: Int = 6 + 16 * 23

  private val report: CorpusReport =
    EmissionCorpus.report.getOrElse(
      fail(
        "the network corpus was not found: set " + NetworkFixtureCorpus.RootVariable + " or write " +
          NetworkFixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  private val unladdered: CorpusReport =
    EmissionCorpus.withoutLadder.getOrElse(fail("the network corpus was not found"))

  "the emission schedule" should "yield every height the corpus states" in
    assert(
      report.casesFound == Vectors,
      "found " + report.casesFound.toString + " rather than " + Vectors.toString + ": " + report.describe
    )

  it should "skip none of them" in
    assert(
      report.skipped.isEmpty,
      "an emission vector states its figures unconditionally, so a skip is a reader fault: " + report.describe
    )

  it should "agree with every figure at every height" in
    assert(report.diverged.isEmpty, report.describe)

  it should "pin the stated number of figures" in
    assert(
      EmissionCorpus.figuresAsserted == Figures,
      "pinned " + EmissionCorpus.figuresAsserted.toString + " figures rather than " + Figures.toString +
        ", so the tier is asserting less than it reports"
    )

  "an engine running no era ladder" should "pay correctly only inside the first era" in
    assert(
      unladdered.agreed.length == WithinFirstEra,
      "an engine with no ladder agreed at " + unladdered.agreed.length.toString + " heights rather than " +
        WithinFirstEra.toString + ", so the corpus does not pin the reduction where it was thought to: " +
        unladdered.describe
    )

  it should "be refused at every height past the first era boundary" in
    assert(
      unladdered.diverged.length == Vectors - WithinFirstEra,
      "the ladder is unpinned at " + (Vectors - WithinFirstEra - unladdered.diverged.length).toString +
        " heights: " + unladdered.describe
    )
