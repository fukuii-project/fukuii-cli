package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.evm.fixtures.{FixtureCorpus, Heavy}

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

object LabelCensus:

  /** Every count a label is pinned to, as `report` gives it, with the cases that
    * diverged in name order.
    */
  def of(report: BlockchainReport): LabelCensus =
    LabelCensus(
      report.filesRead,
      report.cases,
      report.blocksStatedValid,
      report.refusalsStated,
      report.blocksAccepted,
      report.agreed.length,
      report.refusalsAgreed,
      report.divergedNames,
      report.undecidedByRule,
      report.skippedByReason
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
  * that directory is pinned by `EnginePayloadCertificationSpec` at 150 files and
  * 4,997 payloads, one payload per block -- and the 148 files, 4,645 blocks
  * accepted and 288 refused here, with the 2 files and 64 blocks its
  * `ported_static` group holds, are 150 files and 4,997 blocks.
  *
  * ==Each label's `ported_static` group is pinned the same way, under
  * `org.fukuii.Heavy`==
  *
  * `BlockchainCorpus.PortedStaticGroups` states why the groups leave the
  * ordinary run. They are pinned by one tagged property rather than one per
  * label, because its rows differ only in the census each holds, which is the
  * shape this project gives a test matrix; each row compares a group's whole
  * census, so no count goes unasserted, and every row is checked rather than
  * the first that fails, so a run moving several groups names each. The same
  * second reader counted each group's files, cases and stated blocks, and each
  * group's figures with its label's own row are the figures that reader counts
  * over the whole directory.
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
        LabelCensus(148, 3446, 4645, 288, 4645, 3446, 288, Vector.empty, Map.empty, Map.empty),
      "for_shanghai" ->
        LabelCensus(180, 3687, 4903, 302, 4903, 3687, 302, Vector.empty, Map.empty, Map.empty),
      "for_paristoshanghaiattime15k" ->
        LabelCensus(1, 40, 80, 0, 80, 40, 0, Vector.empty, Map.empty, Map.empty),
      "for_shanghaitocancunattime15k" ->
        LabelCensus(12, 60, 288, 8, 288, 60, 8, Vector.empty, Map.empty, Map.empty),
      "for_cancun" ->
        LabelCensus(266, 5848, 6856, 713, 6856, 5848, 713, Vector.empty, Map.empty, Map.empty),
      // The first label whose rules assemble a request list, and the only
      // published exercise of the checked calls' empty-target refusal: two of
      // its six refusals are a system contract undeployed at the transition.
      "for_cancuntopragueattime15k" ->
        LabelCensus(10, 98, 1135, 6, 1135, 98, 6, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at Paris" ->
        LabelCensus(22, 22, 9, 23, 9, 22, 23, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at Shanghai" ->
        LabelCensus(22, 22, 9, 23, 9, 22, 23, Vector.empty, Map.empty, Map.empty),
      "bcInvalidHeaderTest at Cancun" ->
        LabelCensus(22, 22, 9, 23, 9, 22, 23, Vector.empty, Map.empty, Map.empty),
      "for_frontier" ->
        LabelCensus(33, 508, 1201, 10, 1201, 508, 10, Vector.empty, Map.empty, Map.empty),
      "for_homestead" ->
        LabelCensus(36, 523, 1450, 14, 1450, 523, 14, Vector.empty, Map.empty, Map.empty),
      "for_tangerinewhistle" ->
        LabelCensus(42, 718, 1645, 14, 1645, 718, 14, Vector.empty, Map.empty, Map.empty),
      "for_spuriousdragon" ->
        LabelCensus(42, 718, 1643, 15, 1643, 718, 15, Vector.empty, Map.empty, Map.empty),
      "for_byzantium" ->
        LabelCensus(78, 2154, 3453, 15, 3453, 2154, 15, Vector.empty, Map.empty, Map.empty),
      "for_constantinoplefix" ->
        LabelCensus(109, 2276, 3748, 15, 3748, 2276, 15, Vector.empty, Map.empty, Map.empty),
      "for_istanbul" ->
        LabelCensus(115, 2420, 3892, 15, 3892, 2420, 15, Vector.empty, Map.empty, Map.empty),
      "for_berlin" ->
        LabelCensus(143, 3151, 4481, 157, 4481, 3151, 157, Vector.empty, Map.empty, Map.empty),
      "for_london" ->
        LabelCensus(142, 3420, 4619, 288, 4619, 3420, 288, Vector.empty, Map.empty, Map.empty),
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

  /** A group of 64: the static suite's two stack-overflow files at a label's
    * fork, each case one block the corpus states valid.
    */
  private val stackOverflowGroup: LabelCensus =
    LabelCensus(2, 64, 64, 0, 64, 64, 0, Vector.empty, Map.empty, Map.empty)

  /** Each generated label's `ported_static` group, in the order
    * `BlockchainCorpus.PortedStaticGroups` assembles them.
    */
  private val portedStaticCensus: Vector[(String, LabelCensus)] =
    Vector(
      "for_paris/ported_static" -> stackOverflowGroup,
      "for_shanghai/ported_static" -> stackOverflowGroup,
      "for_cancun/ported_static" ->
        LabelCensus(2135, 7040, 6905, 135, 6905, 7040, 135, Vector.empty, Map.empty, Map.empty),
      "for_frontier/ported_static" -> stackOverflowGroup,
      "for_homestead/ported_static" -> stackOverflowGroup,
      "for_tangerinewhistle/ported_static" -> stackOverflowGroup,
      "for_spuriousdragon/ported_static" -> stackOverflowGroup,
      "for_byzantium/ported_static" -> stackOverflowGroup,
      "for_constantinoplefix/ported_static" -> stackOverflowGroup,
      "for_istanbul/ported_static" -> stackOverflowGroup,
      "for_berlin/ported_static" -> stackOverflowGroup,
      "for_london/ported_static" -> stackOverflowGroup
    )

  private val portedStaticCensused = Table(("group", "expected"), portedStaticCensus*)

  property("every label the tier assembles is censused, and in the order it runs") {
    val names = assembled.map(_.corpus)
    assert(
      names == BlockchainCorpus.Labels.map(_.name) && names.toSet == census.keySet,
      "assembled " + names.toString + " against a census of " + census.keySet.toString
    )
  }

  property("the census covers twenty-eight labels, counted") {
    // Dropping a label from the census and from what the tier assembles leaves
    // the two agreeing with each other and every other property passing, so the
    // number of labels is pinned on its own. Raising it is adding a label;
    // lowering it drops certified cases, and that is a decision.
    assert(census.size == 28, "the census covers " + census.size.toString + " labels rather than twenty-eight")
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

  property("every generated label's ported_static group holds the census its row records, all twelve", Heavy) {
    // The assembled groups and the census are compared inside every row, in
    // order and counted, because a group dropped from both would leave every
    // remaining row agreeing.
    val reports = BlockchainCorpus.portedStaticReports.getOrElse(
      fail(
        "the published corpus was not found: set " + FixtureCorpus.RootVariable + " or write " +
          FixtureCorpus.RootPointer.toString + ". A run that cannot find it has measured nothing."
      )
    )
    val assembledGroups = reports.map(_.corpus)
    val censusedGroups = portedStaticCensus.map(_._1)
    forEvery(portedStaticCensused) { (group: String, expected: LabelCensus) =>
      val report = found(reports, group)
      assert(
        assembledGroups == censusedGroups && censusedGroups.length == 12 && LabelCensus.of(report) == expected,
        "assembled " + assembledGroups.mkString(", ") + " against a census of " + censusedGroups.mkString(", ") +
          "; " + report.describe
      )
    }
  }
