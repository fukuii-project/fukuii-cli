package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.{CorpusReport, NetworkFixtureCorpus}

/** What a block credits and to whom, against this chain's own block shapes.
  *
  * ==What these expectations rest on==
  *
  * The corpus names `ethereumclassic/core-geth` v1.12.21-unstable @ `4185df450`
  * as its oracle, read rather than run, and marks nine of its eighteen vectors
  * as carrying a real mainnet block whose credits were confirmed against archive
  * state. The other nine are marked computed and cover shapes mainnet does not
  * conveniently supply -- above all the era boundary, where the same block shape
  * one block apart separates two rules that change together.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * Asserted rather than cancelled, for the reason
  * [[DifficultyCertificationSpec]] states.
  */
class OmmerPaymentCertificationSpec extends AnyFlatSpec:

  /** Block shapes the corpus states. */
  private val Vectors: Int = 18

  /** Of those, the ones carrying a real mainnet block.
    *
    * Stated because it is the part of this tier that is a cross-check against
    * the chain rather than against a reading of a client, and a corpus that
    * quietly lost its observed half would still agree everywhere.
    */
  private val Observed: Int = 9

  /** Vectors inside the first era, which an engine running no ladder pays
    * correctly.
    *
    * The size of that control's blind spot rather than a coverage figure.
    */
  private val WithinFirstEra: Int = 11

  /** Vectors stating no ommer at all, which are unchanged by dropping ommers. */
  private val WithoutAnyOmmer: Int = 1

  private val report: CorpusReport =
    OmmerPaymentCorpus.report.getOrElse(
      fail(
        "the network corpus was not found: set " + NetworkFixtureCorpus.RootVariable + " or write " +
          NetworkFixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  private val unladdered: CorpusReport =
    OmmerPaymentCorpus.withoutLadder.getOrElse(fail("the network corpus was not found"))

  private val unommered: CorpusReport =
    OmmerPaymentCorpus.withoutOmmers.getOrElse(fail("the network corpus was not found"))

  "the ommer payment tier" should "yield every block shape the corpus states" in
    assert(
      report.casesFound == Vectors,
      "found " + report.casesFound.toString + " rather than " + Vectors.toString + ": " + report.describe
    )

  it should "skip none of them" in
    assert(
      report.skipped.isEmpty,
      "a payment vector states its credits unconditionally, so a skip is a reader fault: " + report.describe
    )

  it should "credit exactly the stated addresses at every shape" in
    assert(report.diverged.isEmpty, report.describe)

  it should "still carry the vectors confirmed against the chain" in
    assert(
      OmmerPaymentCorpus.vectors
        .getOrElse(Vector.empty)
        .count(_.grounding == OmmerPaymentVector.Observed) == Observed,
      "the observed half of this tier is what makes it a cross-check rather than a second reading of one client"
    )

  "an engine running no era ladder" should "pay correctly only inside the first era" in
    assert(
      unladdered.agreed.length == WithinFirstEra,
      "an engine with no ladder agreed at " + unladdered.agreed.length.toString + " shapes rather than " +
        WithinFirstEra.toString + ": " + unladdered.describe
    )

  it should "be refused at every shape past the first era boundary" in
    assert(
      unladdered.diverged.length == Vectors - WithinFirstEra,
      "the ladder is unpinned at " + (Vectors - WithinFirstEra - unladdered.diverged.length).toString +
        " shapes: " + unladdered.describe
    )

  "dropping every block's ommers" should "change the answer wherever one is stated" in
    assert(
      unommered.diverged.length == Vectors - WithoutAnyOmmer,
      "a shape whose ommer can be removed without changing what it credits is not asserting the ommer's payment: " +
        unommered.describe
    )

  it should "leave the shape that states no ommer untouched" in
    assert(
      unommered.agreed.length == WithoutAnyOmmer,
      "the control that pays no ommer must be the only one indifferent to dropping them: " + unommered.describe
    )
