package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** What the published state corpora say about the layer that settles a
  * transaction.
  *
  * ==The counts are compared against the other path rather than restated==
  *
  * `CertificationCorporaSpec` holds a census of how many files and cases each
  * corpus has. Writing those figures again here would put one number in two
  * places, and the copy would go on passing after the original moved. So every
  * count below is asserted against the report the other path produced for the
  * same corpus: what this says is *the real layer covers exactly the material
  * the certified driver covers*, which is the claim worth making and is the one
  * a restated figure could not support.
  *
  * ==A missing corpus FAILS here, for the reason it fails there==
  *
  * A canceled test appears in no total, so a run with no corpus would certify
  * nothing and report success. The first case is what makes that state loud.
  */
class SettlementCorporaSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** The settlement path's reports, or a canceled test where there is no
    * corpus.
    *
    * Called before `forAll` and never inside it, for the reason
    * `CertificationCorporaSpec` states: the table driver catches `Throwable` in
    * order to name the failing row, which turns a cancellation into a failure.
    */
  private def settled: Vector[CorpusReport] =
    SettlementCorpora.reports.getOrElse(
      cancel(
        "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
          FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
          " before the sbt server this task runs in was started"
      )
    )

  private def driven: Vector[CorpusReport] =
    CertificationCorpora.reports.getOrElse(cancel("no fixture corpus"))

  private def paired: Vector[(CorpusReport, CorpusReport)] =
    val driver = driven
    settled.map { report =>
      (
        report,
        driver.find(_.corpus == report.corpus).getOrElse(fail("settled a corpus nothing else reads: " + report.corpus))
      )
    }

  property("the fixture corpus is configured, or nothing below this line certifies anything") {
    assert(
      FixtureCorpus.root.isDefined,
      "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
        FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
        " before the sbt server this task runs in was started. Every case below will cancel, and a" +
        " canceled case is counted by nothing -- so without this one the run would certify nothing" +
        " and report success."
    )
  }

  property("every state corpus the harness reads is settled by the real layer") {
    // The interpreter tier is the one the other path has and this one does not:
    // it states an invocation with no transaction around it, so a settlement has
    // nothing to do with it. Everything else must appear on both sides, or this
    // path is certifying a subset while reporting on the whole.
    val here = settled.map(_.corpus).toSet
    val there = driven.map(_.corpus).toSet - CertificationCorpora.LegacyVmCorpus
    assert(here == there, "settled " + here.toString + " against a harness reading " + there.toString)
  }

  property("each corpus reaches the real layer with every file the harness read") {
    forAll(Table("pair", paired*)) { (settlement: CorpusReport, driver: CorpusReport) =>
      assert(settlement.filesRead == driver.filesRead, settlement.describe + " || " + driver.describe)
    }
  }

  property("each corpus reaches the real layer with every case the harness found") {
    forAll(Table("pair", paired*)) { (settlement: CorpusReport, driver: CorpusReport) =>
      assert(settlement.casesFound == driver.casesFound, settlement.describe + " || " + driver.describe)
    }
  }

  property("the real layer skips exactly the cases the harness skips") {
    // The direction that matters. A settlement path that skipped what it could
    // not handle would agree with every case it ran and cover less than the
    // driver, and the count of tests would not move.
    forAll(Table("pair", paired*)) { (settlement: CorpusReport, driver: CorpusReport) =>
      assert(settlement.skipped.length == driver.skipped.length, settlement.describe + " || " + driver.describe)
    }
  }

  property("the real layer agrees with every published case it ran") {
    forAll(Table("pair", paired*)) { (settlement: CorpusReport, _: CorpusReport) =>
      assert(settlement.diverged.isEmpty, settlement.describe)
    }
  }

  property("the real layer and the harness driver reach the same verdict on every case") {
    // The strongest of these, and the one that is not implied by the others: two
    // runs can agree on how many cases diverged while diverging on different
    // ones, and two runs can both be green while disagreeing about a case one
    // skipped and the other ran.
    forAll(Table("pair", paired*)) { (settlement: CorpusReport, driver: CorpusReport) =>
      assert(settlement.outcomes == driver.outcomes, settlement.describe + " || " + driver.describe)
    }
  }
