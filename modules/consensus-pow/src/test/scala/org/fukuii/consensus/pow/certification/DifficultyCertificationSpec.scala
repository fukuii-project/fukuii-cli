package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus}

/** The difficulty rule against every case the published `DifficultyTests` tier
  * states.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * The tier is a third-party artifact assembled beside a clone, so its absence
  * is the ordinary condition on a machine that has not fetched it. It is
  * asserted rather than cancelled: a suite that cancels is counted by nothing,
  * so a build whose corpus vanished would report the same executed total as one
  * that ran it, and `AGENTS.md` records executed-plus-cancelled matching an
  * expected total over a real failure as a measured event in this repository.
  *
  * ==The figures are stated, not derived from the run==
  *
  * Every count below is a literal read off the corpus, so a reader can see the
  * denominator without running anything and a corpus that shrank is a failure
  * rather than a smaller green. Deriving them from the same run they check would
  * make each case true of any run whatsoever.
  */
class DifficultyCertificationSpec extends AnyFlatSpec:

  /** Files in the tier: seventeen, in `ethereum/tests` @ `v17.2` and in
    * `etclabscore/tests-etc` @ `06ec708ea` alike.
    */
  private val Files: Int = 17

  /** Cases across those files.
    *
    * **The two corpora hold the same cases.** The seventeen files differ byte
    * for byte between the two trees and every case inside them is identical;
    * what differs is the `_info` block naming the tool that filled them. So the
    * Ethereum Classic tier is a mirror at this level and adds no case of its
    * own.
    */
  private val Cases: Int = 18598

  private val report: CorpusReport =
    DifficultyCorpus.report.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  /** The seven fork names the tier files its cases under.
    *
    * Stated so that a corpus gaining a fork this harness has no rules for is a
    * failure here rather than a silent divergence counted one case at a time,
    * and so that a harness losing one is visible as the coverage change it is.
    */
  private val Forks: Set[String] =
    Set("Frontier", "Homestead", "Byzantium", "Constantinople", "Berlin", "ArrowGlacier", "GrayGlacier")

  "the published difficulty tier" should "be read in full" in
    assert(
      report.filesRead == Files,
      "read " + report.filesRead.toString + " files rather than " + Files.toString + ": " + report.describe
    )

  it should "yield every case the tier states" in
    assert(
      report.casesFound == Cases,
      "found " + report.casesFound.toString + " cases rather than " + Cases.toString + ": " + report.describe
    )

  it should "skip none of them" in
    assert(
      report.skipped.isEmpty,
      "a difficulty case states its expectation unconditionally, so a skip is a reader fault: " + report.describe
    )

  it should "agree with every one" in
    assert(
      report.diverged.isEmpty,
      report.describe
    )

  "the harness" should "state rules for every fork the tier names and for no other" in
    assert(
      Forks.forall(DifficultyCorpus.rulesFor(_).isDefined) &&
        DifficultyCorpus.rulesFor("Paris").isEmpty,
      "a fork the corpus names and the harness cannot resolve is counted as a divergence per case, not as a gap"
    )
