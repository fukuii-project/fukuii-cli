package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.evm.fixtures.FixtureCorpus

/** What one label states and what this build did with it, as pinned counts. */
final case class LabelCensus(
    files: Int,
    cases: Int,
    blocksStatedValid: Int,
    refusalsStated: Int,
    blocksAccepted: Int,
    agreed: Int,
    refusalsAgreed: Int,
    diverged: Vector[String],
    undecided: Map[String, Int],
    skipped: Map[String, Int]
)

/** The published block tier, run through this build's block validator and
  * pinned as counts, one row per label.
  *
  * ==Every count is asserted, in both directions==
  *
  * A harness that narrowed its own input agrees with everything left, and every
  * figure it reports stays plausible. So the files read, the cases found, the
  * blocks the corpus states, the blocks this build accepted, the refusals it
  * agreed with, and every case it did not agree with -- diverged, undecided or
  * skipped -- are each pinned exactly, and a change that moves any of them fails
  * an ordinary run whichever way it moves.
  *
  * ==A divergence is pinned by the case's name, not only by a count==
  *
  * A label whose disagreements have a known cause and no fix here still runs,
  * and what holds it is the list of the cases that diverge. A count alone would
  * let one divergence give way to another and read unchanged.
  *
  * ==The stated counts are held to counts this reader did not produce==
  *
  * Files, cases, blocks the corpus states valid and blocks it states refused
  * were also counted over every label by a second reader sharing no code with
  * this one, with its own JSON parser and RLP decoder, and it reports the same
  * four figures in each. **For `for_paris` another tier in this build
  * corroborates them from the other side**: the same release's engine form of
  * that label is pinned by `EnginePayloadCertificationSpec` at 150 files and
  * 4,997 payloads, one payload per block -- and 4,709 accepted plus 288 refused
  * is 4,997.
  *
  * **A missing corpus fails rather than cancels**, and the reports are read
  * before `forAll` and never inside it, for the reason
  * `org.fukuii.chainspec.certification.CertificationCorporaSpec` records:
  * `forAll` catches what a row raises, and a `fail` from inside a row would
  * report as that row rather than as the absent corpus.
  *
  * `BlockchainCertificationSpec` holds the calibration these rows depend on: a
  * published case per comparison, seeded with one defect each, that the same
  * runner and report must count as a divergence.
  */
class BlockchainCertificationPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** A label from `ethereum/legacytests`' `Constantinople` snapshot of
    * `bcInvalidHeaderTest`, each of whose seven networks states 21 refusals over
    * 21 cases and one block accepted before one of them.
    */
  private val olderInvalidHeaders: LabelCensus =
    LabelCensus(21, 21, 1, 21, 1, 21, 21, Vector.empty, Map.empty, Map.empty)

  private val census: Map[String, LabelCensus] =
    Map(
      "for_paris" ->
        LabelCensus(150, 3510, 4709, 288, 4709, 3510, 288, Vector.empty, Map.empty, Map.empty),
      "for_shanghai" ->
        LabelCensus(182, 3751, 4967, 302, 4967, 3751, 302, Vector.empty, Map.empty, Map.empty),
      "for_paristoshanghaiattime15k" ->
        LabelCensus(1, 40, 80, 0, 80, 40, 0, Vector.empty, Map.empty, Map.empty),
      "for_shanghaitocancunattime15k" ->
        LabelCensus(12, 60, 288, 8, 288, 60, 8, Vector.empty, Map.empty, Map.empty),
      "for_cancun" ->
        LabelCensus(266, 5848, 6856, 713, 6856, 5848, 713, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at Paris" ->
        LabelCensus(22, 22, 9, 23, 9, 22, 23, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at Shanghai" ->
        LabelCensus(22, 22, 9, 23, 9, 22, 23, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at Cancun" ->
        LabelCensus(22, 22, 9, 23, 9, 22, 23, Vector.empty, Map.empty, Map.empty),
      "for_frontier" ->
        LabelCensus(35, 572, 1265, 10, 1265, 572, 10, Vector.empty, Map.empty, Map.empty),
      "for_homestead" ->
        LabelCensus(38, 587, 1514, 14, 1514, 587, 14, Vector.empty, Map.empty, Map.empty),
      "for_tangerinewhistle" ->
        LabelCensus(44, 782, 1709, 14, 1709, 782, 14, Vector.empty, Map.empty, Map.empty),
      "for_spuriousdragon" ->
        LabelCensus(44, 782, 1707, 15, 1707, 782, 15, Vector.empty, Map.empty, Map.empty),
      "for_byzantium" ->
        LabelCensus(80, 2218, 3517, 15, 3517, 2218, 15, Vector.empty, Map.empty, Map.empty),
      "for_constantinoplefix" ->
        LabelCensus(111, 2340, 3812, 15, 3812, 2340, 15, Vector.empty, Map.empty, Map.empty),
      "for_istanbul" ->
        LabelCensus(117, 2484, 3956, 15, 3956, 2484, 15, Vector.empty, Map.empty, Map.empty),
      "for_berlin" ->
        LabelCensus(145, 3215, 4545, 157, 4545, 3215, 157, Vector.empty, Map.empty, Map.empty),
      "for_london" ->
        LabelCensus(144, 3484, 4683, 288, 4683, 3484, 288, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest (Constantinople snapshot) at Frontier" -> olderInvalidHeaders,
      "bcInvalidHeaderTest (Constantinople snapshot) at Homestead" -> olderInvalidHeaders,
      "bcInvalidHeaderTest (Constantinople snapshot) at EIP150" -> olderInvalidHeaders,
      "bcInvalidHeaderTest (Constantinople snapshot) at EIP158" -> olderInvalidHeaders,
      "bcInvalidHeaderTest (Constantinople snapshot) at Byzantium" -> olderInvalidHeaders,
      "bcInvalidHeaderTest (Constantinople snapshot) at Constantinople" -> olderInvalidHeaders,
      "bcInvalidHeaderTest (Constantinople snapshot) at ConstantinopleFix" -> olderInvalidHeaders,
      "bcInvalidHeaderTest at Istanbul" ->
        LabelCensus(22, 21, 1, 21, 1, 21, 21, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at Berlin" ->
        LabelCensus(22, 21, 1, 21, 1, 21, 21, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at London" ->
        LabelCensus(22, 22, 8, 24, 8, 22, 24, Vector.empty, Map.empty, Map.empty)
    )

  private val censused = Table(("label", "expected"), census.toSeq.sortBy(_._1)*)

  private def assembled: Vector[BlockchainReport] =
    BlockchainCorpus.reports.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )

  private def found(reports: Vector[BlockchainReport], label: String): BlockchainReport =
    reports.find(_.corpus == label).getOrElse(fail("censused but never assembled: " + label))

  property("every label the tier assembles is censused, and in the order it runs") {
    val names = assembled.map(_.corpus)
    assert(
      names == BlockchainCorpus.Labels.map(_.name) && names.toSet == census.keySet,
      "assembled " + names.toString + " against a census of " + census.keySet.toString
    )
  }

  property("the census covers twenty-seven labels, counted") {
    // Dropping a label from the census and from what the tier assembles leaves
    // the two agreeing with each other and every other property passing, so the
    // number of labels is pinned on its own. Raising it is adding a label;
    // lowering it drops certified cases, and that is a decision.
    assert(census.size == 27, "the census covers " + census.size.toString + " labels rather than twenty-seven")
  }

  property("every label reads the files the census records") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val read = report.filesRead
      assert(read == expected.files, report.describe)
    }
  }

  property("every label finds the cases the census records") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val cases = report.cases
      assert(cases == expected.cases, report.describe)
    }
  }

  property("every label reads the blocks its corpus states, as the independent census counts them") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val valid = report.blocksStatedValid
      val refused = report.refusalsStated
      assert(valid == expected.blocksStatedValid && refused == expected.refusalsStated, report.describe)
    }
  }

  property("every label accepts exactly the pinned number of blocks") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val accepted = report.blocksAccepted
      assert(accepted == expected.blocksAccepted, report.describe)
    }
  }

  property("every label agrees with exactly the pinned number of cases") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val agreed = report.agreed.length
      assert(agreed == expected.agreed, report.describe)
    }
  }

  property("every label refuses exactly the pinned number of blocks under a name their case states") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val refused = report.refusalsAgreed
      assert(refused == expected.refusalsAgreed, report.describe)
    }
  }

  property("every label diverges on exactly the cases pinned, by name") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val diverged = report.divergedNames
      assert(diverged == expected.diverged.sorted, report.describe)
    }
  }

  property("every label leaves exactly the pinned cases undecided, by rule") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val undecided = report.undecidedByRule
      assert(undecided == expected.undecided, report.describe)
    }
  }

  property("every label skips exactly the pinned cases, by reason") {
    val reports = assembled
    forAll(censused) { (label: String, expected: LabelCensus) =>
      val report = found(reports, label)
      val skipped = report.skippedByReason
      assert(skipped == expected.skipped, report.describe)
    }
  }

  property("every label accounts for every case under exactly one outcome") {
    val reports = assembled
    forAll(censused) { (label: String, _: LabelCensus) =>
      val report = found(reports, label)
      val accounted =
        report.agreed.length + report.diverged.length + report.undecidedByRule.values.sum +
          report.skippedByReason.values.sum
      assert(accounted == report.cases, report.describe)
    }
  }
