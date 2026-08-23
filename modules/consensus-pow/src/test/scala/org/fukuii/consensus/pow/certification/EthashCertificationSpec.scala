package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.evm.fixtures.{CorpusReport, FixtureCorpus}

/** The seal against every case the published `PoWTests` tier states.
  *
  * ==A corpus that could not be found is a failure and never a pass==
  *
  * The tier is a third-party artifact assembled beside a clone, so its absence
  * is the ordinary condition on a machine that has not fetched it. It is
  * asserted rather than cancelled, for the reason `DifficultyCertificationSpec`
  * gives: a suite that cancels is counted by nothing, so a build whose corpus
  * vanished reports the same executed total as one that ran it.
  *
  * ==The figures are stated, not derived from the run==
  *
  * Every count below is a literal read off the corpus, so a corpus that shrank
  * is a failure rather than a smaller green.
  */
class EthashCertificationSpec extends AnyFlatSpec:

  /** Files in the tier: one, `ethash_tests.json`. */
  private val Files: Int = 1

  /** Cases across that file.
    *
    * **Two, and all three corpora carrying this tier hold the same file byte
    * for byte** -- `ethereum/tests` @ `v17.2`, `etclabscore/tests` @
    * `0ca936b392` and `etclabscore/tests-etc` @ `06ec708ea7` agree to the
    * digest. So the Ethereum Classic corpora add no case of their own here,
    * and this tier is two cases in total rather than two per corpus.
    */
  private val Cases: Int = 2

  private val report: CorpusReport =
    EthashCorpus.report.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  "the published proof-of-work tier" should "be read in full" in
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
      "a proof-of-work case states its expectations unconditionally, so a skip is a reader fault: " + report.describe
    )

  it should "agree with every one at every point it states" in
    assert(report.diverged.isEmpty, report.describe)
