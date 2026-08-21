package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path
import scala.util.control.NonFatal

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{Network, UpgradeSchedule}
import org.fukuii.chainspec.networks.{KnownNetworks, ethereum, ethereumclassic}
import org.fukuii.evm.EvmRules

/** The published corpora this layer is certified against, run once and reported
  * as counts.
  *
  * ==A tier is a directory and a fork key, and neither implies the other==
  *
  * The generated tier partitions by fork, so a directory names one; the legacy
  * tier does not, so one directory answers for every fork it carries an
  * expectation under and is read once per fork. A report is therefore keyed by
  * both, and the count of reports is not the count of directories.
  *
  * ==Computed once, because running them is the expensive part==
  *
  * Every spec that asserts something about coverage asserts it about the same
  * run, so the run happens on first demand and the reports are shared. Two
  * suites asking two questions of two different runs could disagree about how
  * many cases there were.
  */
object CertificationCorpora:

  /** Every report, or nothing at all when the harness cannot be assembled.
    *
    * The distinction is the point: a harness that answered with empty reports
    * would be indistinguishable from one that found nothing wrong. `None` is
    * caught loudly by the first case in `CertificationCorporaSpec`, which is
    * why every step below may collapse into it rather than reporting its own
    * failure -- a broken registry is separately and loudly asserted by
    * `KnownNetworksSpec`, so nothing here has to restate that diagnosis.
    *
    * ==The rules come from the registry, not from a named composition==
    *
    * Each corpus below is bound to a NETWORK and a HEIGHT, and what runs is
    * whatever that network's schedule says is in force there. Naming a
    * composition instead would certify the compositions and say nothing about
    * the schedule, because a composition is right by construction while an
    * activation is an external fact nothing here can derive.
    */
  lazy val reports: Option[Vector[CorpusReport]] =
    for
      root <- FixtureCorpus.root
      registry <- KnownNetworks.registry.toOption
      ethereum <- registry.at(ethereum.Mainnet.network.chainId)
      classic <- registry.at(ethereumclassic.Mainnet.network.chainId)
    yield assemble(root, ethereum, classic)

  /** The legacy hand-written interpreter tier: an invocation stated directly,
    * with no transaction around it.
    */
  val LegacyVmCorpus: String = "legacytests Constantinople/VMTests"

  /** The legacy hand-written state tier, of which only the cases carrying a
    * Frontier expectation are executable here.
    *
    * The fork is named in the report because this directory is read more than
    * once. A general state test states expectations under several forks at
    * once, so the same 2394 files answer a different question per fork asked --
    * and a report naming only the directory could not say which was asked.
    */
  val LegacyFrontierStateCorpus: String = "legacytests Constantinople/GeneralStateTests at Frontier"

  /** The same directory, read for EIP-150's expectations instead.
    *
    * **These cases are not a subset of the suites named for that proposal, and
    * the difference is thirteenfold.** Four suites carry it in their names and
    * hold 81 cases between them; the fork key is carried by 650 cases across the
    * tier, expanding to 1096 runnable combinations. The suite name under-reports
    * because a general state test is not organized by the fork it exercises,
    * and the post key is what the reader dispatches on -- which is the invariant
    * to search for rather than the name.
    */
  val LegacyEip150StateCorpus: String = "legacytests Constantinople/GeneralStateTests at EIP150"

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

  /** The same tier filled for the fork after that.
    *
    * **The directory is partitioned by the fork a test was FILLED FOR, not by
    * the fork it was authored at**, so it holds families named for much later
    * proposals evaluated under these rules. What it certifies is therefore every
    * published state test filled for this fork, which is a different set from
    * every test of this fork and is the only claim the directory supports.
    */
  val GeneratedTangerineWhistleCorpus: String = "execution-specs-fixtures state_tests/for_tangerinewhistle"

  /** The same directory as the tier above, resolved through the other network's
    * schedule instead.
    *
    * ==One corpus, two schedules, and that is the whole claim==
    *
    * Both networks adopted EIP-150 unaltered, so a case filled for that fork is
    * a case either of them must satisfy. What differs is WHERE each switched it
    * on -- block 2,463,000 and block 2,500,000 -- so running one corpus through
    * both schedules at each network's own height exercises the two activations
    * against material neither of them can influence.
    *
    * **This is the only corpus whose passing depends on Ethereum Classic's
    * activation being right.** Move that block and every case here is resolved
    * under the rules of the fork before it.
    */
  val ClassicTangerineWhistleCorpus: String =
    "execution-specs-fixtures state_tests/for_tangerinewhistle through Ethereum Classic"

  /** The height at which each corpus asks its network what is in force.
    *
    * ==These figures are the harness's, and duplicating the schedule's is the
    * mechanism rather than an oversight==
    *
    * A schedule states where a fork begins. These state where the harness
    * BELIEVES it begins, which is what makes the two comparable: the corpus is
    * filled for a named fork, it is run at the height that fork is supposed to
    * start at, and the schedule answers with whatever it actually holds there.
    * When the two disagree the corpus is resolved under a neighbouring fork's
    * rules and diverges.
    *
    * **So do not replace these by reading the activation off the schedule.**
    * That closes the loop: the harness would ask the schedule where the fork is
    * and then ask the same schedule what runs there, which is true of any
    * schedule whatsoever and certifies nothing.
    *
    * Each figure is cited on the schedule entry that is supposed to match it.
    */
  private[certification] val EthereumFrontierStarts: Long = 0L
  private[certification] val EthereumHomesteadStarts: Long = 1150000L
  private[certification] val EthereumTangerineWhistleStarts: Long = 2463000L
  private[certification] val ClassicGasRepriceStarts: Long = 2500000L

  /** Every network-and-height pair the corpora above are resolved at.
    *
    * Built from the same four constants [[assemble]] uses, so a figure moved
    * for one is moved for both -- which is the intent, because the figure is
    * the thing under test. What this does NOT close is a corpus added later at
    * a fifth height and never listed here; the count property beside the census
    * is what makes adding a corpus a visible act.
    */
  private[certification] val resolutionPoints: Vector[(Network, Long)] =
    Vector(
      ethereum.Mainnet.network -> EthereumFrontierStarts,
      ethereum.Mainnet.network -> EthereumHomesteadStarts,
      ethereum.Mainnet.network -> EthereumTangerineWhistleStarts,
      ethereumclassic.Mainnet.network -> ClassicGasRepriceStarts
    )

  /** What a network runs at a height, taken from that network's schedule.
    *
    * The whole of the indirection between a corpus and the rules it runs under.
    * Nothing downstream names a composition, so every corpus below is certifying
    * an activation as well as a machine.
    */
  private def rulesAt(schedule: UpgradeSchedule, height: Long): EvmRules =
    schedule.at(UInt64.fromBits(height), UInt64.Zero).evm

  private def assemble(root: Path, ethereum: UpgradeSchedule, classic: UpgradeSchedule): Vector[CorpusReport] =
    val frontier = rulesAt(ethereum, EthereumFrontierStarts)
    val homestead = rulesAt(ethereum, EthereumHomesteadStarts)

    // Bound once, because the two tiers below reach these rules through corpora
    // that name the fork differently -- `TangerineWhistle` in the generated
    // tier, `EIP150` in the legacy one. Two resolutions would let the two drift
    // into certifying different machines under one section's name.
    val tangerineWhistle = rulesAt(ethereum, EthereumTangerineWhistleStarts)

    val gasReprice = rulesAt(classic, ClassicGasRepriceStarts)

    Vector(
      vmReport(FixtureCorpus.legacy(root).resolve("VMTests"), frontier),
      stateReport(LegacyFrontierStateCorpus, FixtureCorpus.legacy(root).resolve("GeneralStateTests"), rules = frontier),
      stateReport(
        GeneratedStateCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_frontier"),
        rules = frontier
      ),
      stateReport(
        LegacyEip150StateCorpus,
        FixtureCorpus.legacy(root).resolve("GeneralStateTests"),
        fork = "EIP150",
        rules = tangerineWhistle
      ),
      stateReport(
        GeneratedHomesteadCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_homestead"),
        fork = "Homestead",
        rules = homestead
      ),
      stateReport(
        GeneratedTangerineWhistleCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_tangerinewhistle"),
        fork = "TangerineWhistle",
        rules = tangerineWhistle
      ),
      stateReport(
        ClassicTangerineWhistleCorpus,
        FixtureCorpus.generated(root).resolve("state_tests/for_tangerinewhistle"),
        fork = "TangerineWhistle",
        rules = gasReprice
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
  private[certification] def outcomeOf(name: String)(running: => Verdict): CaseOutcome =
    val verdict =
      try running
      catch
        case NonFatal(cause) =>
          Verdict.Diverged(Vector("threw " + cause.getClass.getName + ": " + cause.getMessage))
    CaseOutcome(name, verdict)

  private def vmReport(directory: Path, rules: EvmRules): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(directory)
    val outcomes = files.flatMap { file =>
      FixtureCorpus
        .read(file)
        .flatMap(VmFixture.decodeFile(file.getFileName.toString, _)) match
        case Left(error) =>
          Vector(CaseOutcome(file.getFileName.toString, Verdict.Skipped(SkipReason.Undecodable(error))))
        case Right(fixtures) =>
          fixtures.map(fixture => outcomeOf(fixture.name)(VmFixtureRunner.run(fixture, rules)))
    }
    CorpusReport(LegacyVmCorpus, files.length, outcomes)

  private def stateReport(
      name: String,
      directory: Path,
      fork: String = StateFixture.Fork,
      rules: EvmRules
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
