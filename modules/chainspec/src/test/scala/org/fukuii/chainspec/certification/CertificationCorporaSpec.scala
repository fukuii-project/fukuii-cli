package org.fukuii.chainspec.certification

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.KnownNetworks
import org.fukuii.chainspec.{Activation, Network, Registry}
import org.fukuii.evm.fixtures.*

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** How much of a corpus was there, and how much of it ran.
  *
  * These figures are a record of the corpus at the refs the manifest names, not
  * a target. They are asserted so that a corpus which has moved, or a reader
  * which has begun dropping cases, fails rather than reporting a smaller run as
  * a clean one.
  */
final case class CorpusCensus(files: Int, cases: Int, skipped: Int)

/** The certification run: every published fixture this layer can reach, at every
  * fork it has rules for, against the machine.
  *
  * ==A missing corpus FAILS here, and the individual cases still cancel==
  *
  * The corpora are third-party artifacts of tens of megabytes and are assembled
  * beside a clone rather than inside it. Without them there is nothing to
  * measure, and a case that passed in that state would report conformance it
  * never checked -- so each one cancels, naming the variable that supplies the
  * corpus. `FixtureCalibrationSpec` is what still runs, and what shows the
  * harness would notice a divergence if it saw one.
  *
  * **Cancelling is the right answer for a case and the wrong answer for the
  * run.** A canceled test appears in no total ScalaTest reports, so a build with
  * no corpus certified nothing while sbt, the executed count and every exit code
  * agreed it had passed. The first case below is what makes that state loud: it
  * asserts the corpus is configured at all, so it FAILS rather than cancelling,
  * and one failing test is a signal every layer above already understands.
  *
  * It is checked here rather than in the shell because the shell can only read
  * the console, and any check built on that text is coupled to how ScalaTest
  * chooses to print. An assertion is coupled to nothing.
  *
  * ==Named for what it does rather than for a fork==
  *
  * It began as one fork's certification and is now several, which is why neither
  * this suite nor the object it drives carries a fork's name any more. A shared
  * thing named for one network's release of it invites the next reader to treat
  * a per-fork fact as a general one, and the cost of the rename rises with every
  * fork added. The fork names live on the individual corpora, where each one
  * correctly labels the expectations that corpus is read for.
  */
class CertificationCorporaSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val census: Map[String, CorpusCensus] = Map(
    CertificationCorpora.LegacyVmCorpus -> CorpusCensus(files = 609, cases = 609, skipped = 0),
    CertificationCorpora.LegacyFrontierStateCorpus -> CorpusCensus(files = 2394, cases = 2691, skipped = 1668),
    CertificationCorpora.GeneratedStateCorpus -> CorpusCensus(files = 31, cases = 530, skipped = 0),
    CertificationCorpora.GeneratedHomesteadCorpus -> CorpusCensus(files = 34, cases = 545, skipped = 0),
    // The same 2394 files as the Frontier row, asked a different question. Every
    // figure differs: a case counts as found when it states an expectation at
    // the fork asked about OR states none, and a case that states one expands
    // into a run per post entry -- so 650 cases carrying this key become 1096.
    CertificationCorpora.LegacyEip150StateCorpus -> CorpusCensus(files = 2394, cases = 2840, skipped = 1744),
    CertificationCorpora.GeneratedTangerineWhistleCorpus -> CorpusCensus(files = 33, cases = 536, skipped = 0),
    // The same 33 files as the row above, resolved through the other network's
    // schedule at that network's own activation. The figures are identical
    // because the corpus is: what differs is which schedule was asked, and at
    // what height.
    CertificationCorpora.ClassicTangerineWhistleCorpus -> CorpusCensus(files = 33, cases = 536, skipped = 0)
  )

  /** Every censused corpus, as the rows the four properties below drive.
    *
    * ==Built FROM the census, so a corpus cannot be censused without being
    * asserted==
    *
    * Written out per corpus instead, a corpus could be added to the census AND
    * to what the harness assembles and simply never be asserted about: it would
    * run, its divergences would be discarded, and no count would move, because
    * the number of TESTS would be unchanged. Rows derived from the census have
    * no such step to forget.
    *
    * Three things below close the set between them. A corpus assembled and not
    * censused fails the property that compares the two names; a corpus censused
    * and not assembled fails these rows; and a corpus dropped from BOTH leaves
    * those two agreeing with each other, so a third counts the census instead.
    */
  private val censused = Table(("corpus", "expected"), census.toSeq.sortBy(_._1)*)

  private val registry: Registry =
    KnownNetworks.registry.getOrElse(fail("the authored networks do not form a registry"))

  /** Every network-and-height pair the harness resolves rules at. */
  private val resolutions = Table(("network", "height"), CertificationCorpora.resolutionPoints*)

  /** The assembled reports, or a canceled test where there is no corpus.
    *
    * **Called before `forAll` and never inside it.** `TableForN.forAll` catches
    * `Throwable` in order to attach the failing row, which turns the exception a
    * cancellation is carried by into a failure. Raised per row, the absence of a
    * corpus would therefore report as every property FAILING rather than as
    * every case cancelling -- and a build with no corpus would be
    * indistinguishable from a broken machine.
    */
  private def assembled: Vector[CorpusReport] =
    CertificationCorpora.reports.getOrElse(
      cancel(
        "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
          FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
          " before the sbt server this task runs in was started"
      )
    )

  private def found(reports: Vector[CorpusReport], corpus: String): CorpusReport =
    reports.find(_.corpus == corpus).getOrElse(fail("censused but never assembled: " + corpus))

  property("the fixture corpus is configured, or nothing below this line certifies anything") {
    // The one case here that does not cancel when the corpus is absent, and the
    // whole of what makes that state visible. Everything after it measures the
    // machine; this measures whether there was anything to measure it against.
    assert(
      FixtureCorpus.root.isDefined,
      "no fixture corpus: write the directory holding one subdirectory per upstream organization into " +
        FixtureCorpus.RootPointer.toString + ", or set " + FixtureCorpus.RootVariable +
        " before the sbt server this task runs in was started. Every case below will cancel, and a" +
        " canceled case is counted by nothing -- so without this one the run would certify nothing" +
        " and report success."
    )
  }

  property("every corpus the harness assembles is censused") {
    // One half of the pair. A corpus the harness assembles but never censuses
    // would run with nothing asking it anything, and no count would move,
    // because the number of TESTS would be unchanged. The other half is the
    // table above, which derives its rows from the census so that the reverse --
    // censused and never assembled -- cannot happen either.
    val names = assembled.map(_.corpus).toSet
    assert(names == census.keySet, s"assembled ${names.toString} against a census of ${census.keySet.toString}")
  }

  property("the census covers seven corpora, counted") {
    // THE REMOVAL CASE, which the pairing cannot see. Dropping a corpus from the
    // census AND from what the harness assembles leaves those two agreeing with
    // each other, leaves the same six properties registered, and leaves the
    // expected total unmoved -- so a tier can be deleted with every signal green.
    // Deriving the rows from the census closed the addition case and left this
    // one open in the same shape.
    //
    // It matters because of when it happens: deleting a row is the move
    // available to whoever needs a red build green after an upstream corpus
    // moves, which is exactly the moment a ratchet earns its keep.
    //
    // Seven: the interpreter tier, the state tier read for Frontier and again
    // for EIP-150, the generated state tier filled for Frontier, Homestead and
    // Tangerine Whistle, and that last one read a second time through the other
    // network's schedule. Raising this is adding a corpus. Lowering it is
    // dropping certified cases, and that is a decision rather than a tidy-up.
    assert(census.size == 7, s"the census covers ${census.size.toString} corpora rather than seven")
  }

  property("every censused corpus holds the files the census records") {
    val reports = assembled
    forAll(censused) { (corpus: String, expected: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.filesRead == expected.files, report.describe)
    }
  }

  property("every censused corpus holds the cases the census records") {
    val reports = assembled
    forAll(censused) { (corpus: String, expected: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.casesFound == expected.cases, report.describe)
    }
  }

  property("every censused corpus skips exactly the cases the census records") {
    val reports = assembled
    forAll(censused) { (corpus: String, expected: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.skipped.length == expected.skipped, report.describe)
    }
  }

  property("every censused corpus agrees with every case it ran") {
    val reports = assembled
    forAll(censused) { (corpus: String, _: CorpusCensus) =>
      val report = found(reports, corpus)
      assert(report.diverged.isEmpty, report.describe)
    }
  }

  property("one corpus run through both networks' schedules reaches the same verdict on every case") {
    // The strongest thing two networks in one build can say to each other. Both
    // adopted EIP-150 unaltered and switched it on 37,000 blocks apart, so this
    // pair of reports differs in exactly one input -- which schedule was asked,
    // and at which height -- and must differ in no output.
    //
    // Comparing the verdicts rather than the counts is deliberate: two runs can
    // agree on how many cases diverged while diverging on different ones.
    val reports = assembled
    val throughEthereum = found(reports, CertificationCorpora.GeneratedTangerineWhistleCorpus)
    val throughClassic = found(reports, CertificationCorpora.ClassicTangerineWhistleCorpus)
    assert(
      throughEthereum.outcomes == throughClassic.outcomes,
      throughEthereum.describe + " || " + throughClassic.describe
    )
  }

  property("no corpus is resolved through a height that is not an activation on its network") {
    // What stops the heights above being quietly slid to somewhere convenient
    // after a divergence. Each one must be a point the network actually forks
    // at, which the schedule states and the harness does not.
    forAll(resolutions) { (network: Network, height: Long) =>
      val schedule = registry.at(network.chainId).getOrElse(fail("no schedule for " + network.name))
      assert(
        height == 0L || schedule.forkPoints.contains(Activation.AtBlock(UInt64.fromBits(height))),
        network.name + " is asked for its rules at block " + height.toString +
          ", which is not an activation on its schedule: " + schedule.forkPoints.toString
      )
    }
  }
