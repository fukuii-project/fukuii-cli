package org.fukuii.evm.fixtures

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.evm.ChainRules

/** The three published corpora this fork is certified against, run once and
  * reported as counts.
  *
  * ==Computed once, because running them is the expensive part==
  *
  * Every spec that asserts something about coverage asserts it about the same
  * run, so the run happens on first demand and the reports are shared. Two
  * suites asking two questions of two different runs could disagree about how
  * many cases there were.
  */
object FrontierCorpus:

  /** Every report, or nothing at all when the corpus root is not configured.
    *
    * The distinction is the point: a harness that answered with three empty
    * reports would be indistinguishable from one that found nothing wrong.
    */
  lazy val reports: Option[Vector[CorpusReport]] = FixtureCorpus.root.map(assemble)

  /** The legacy hand-written interpreter tier: an invocation stated directly,
    * with no transaction around it.
    */
  val LegacyVmCorpus: String = "legacytests Constantinople/VMTests"

  /** The legacy hand-written state tier, of which only the cases carrying a
    * Frontier expectation are executable here.
    */
  val LegacyStateCorpus: String = "legacytests Constantinople/GeneralStateTests"

  /** The generated state tier, from the tests@v20.0.1 release. */
  val GeneratedStateCorpus: String = "execution-specs-fixtures state_tests/for_frontier"

  /** The same tier filled for the next fork, run under that fork's rules.
    *
    * The corpus is partitioned per fork, so certifying against another one is a
    * directory, a name to read expectations under, and a set of rules -- and
    * nothing else. That it costs no more than this is the fork seam's claim
    * about itself, tested here rather than asserted.
    */
  val GeneratedHomesteadCorpus: String = "execution-specs-fixtures state_tests/for_homestead"

  def reportFor(name: String): Option[CorpusReport] = reports.flatMap(_.find(_.corpus == name))

  private def assemble(root: Path): Vector[CorpusReport] =
    Vector(
      vmReport(FixtureCorpus.legacy(root).resolve("VMTests")),
      stateReport(LegacyStateCorpus, FixtureCorpus.legacy(root).resolve("GeneralStateTests")),
      stateReport(GeneratedStateCorpus, FixtureCorpus.generated(root).resolve("state_tests/for_frontier")),
      stateReport(
        GeneratedHomesteadCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_homestead"),
        fork = "Homestead",
        rules = ChainRules.Homestead.copy(precompiles = VmFixtureRunner.precompiles)
      )
    )

  /** What running one case established, with a case that THREW recorded as a
    * divergence rather than as a skip.
    *
    * ==A throw is a divergence, and calling it a skip fails open==
    *
    * A skip means there was nothing here to compare. A throw means the machine
    * broke on something there was. Counting the second as the first would let a
    * machine that threw on every case report as entirely skipped and therefore
    * green -- the same shape [[FixtureCorpus.read]] records one layer up, where
    * an unreadable file aborted every test in the suite instead of becoming one
    * counted outcome. That boundary was put at the reader and not at the runner,
    * and this is the other half of it.
    *
    * ==Without it, one throwing case costs the whole run and says nothing==
    *
    * The reports are assembled inside a `lazy val`, so an initializer that
    * throws leaves it uninitialized and the next access starts again. Every test
    * that asks for a report therefore re-runs every corpus from the first tier,
    * and the failure surfaces as a run that produces no output for as long as
    * anyone is willing to wait rather than as an error naming a case.
    *
    * `NonFatal` for the reason [[FixtureCorpus.read]] gives: an
    * `OutOfMemoryError` should stop the run rather than be recorded as a wrong
    * answer.
    */
  private[fixtures] def outcomeOf(name: String)(running: => Verdict): CaseOutcome =
    val verdict =
      try running
      catch
        case NonFatal(cause) =>
          Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome(name, verdict)

  private def vmReport(directory: Path): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(directory)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(VmFixture.decodeFile(file.getFileName.toString, _)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(fixtures) =>
          fixtures.map(fixture => outcomeOf(fixture.name)(VmFixtureRunner.run(fixture)))
    }
    CorpusReport(LegacyVmCorpus, files.length, outcomes)

  private def stateReport(
      name: String,
      directory: Path,
      fork: String = StateFixture.Fork,
      rules: ChainRules = StateFixtureRunner.Baseline
  ): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(directory)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(StateFixture.decodeFile(file.getFileName.toString, _, fork)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(contents) =>
          val skipped = contents.withoutExpectation.map { case_ =>
            CaseOutcome(case_, Verdict.Skipped(SkipReason.NoExpectationAtThisFork))
          }
          val run =
            contents.fixtures.map(fixture => outcomeOf(fixture.name)(StateFixtureRunner.run(fixture, rules)))
          skipped ++ run
    }
    CorpusReport(name, files.length, outcomes)
