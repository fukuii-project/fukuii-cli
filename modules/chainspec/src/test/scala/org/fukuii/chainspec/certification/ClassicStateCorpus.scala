package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{UpgradeRules, UpgradeSchedule}
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}

/** Ethereum Classic's own state fixtures, run at that chain's schedule.
  *
  * ==Why this is a second harness and not more rows in the published one==
  *
  * [[CertificationCorpora]] is rooted at [[FixtureCorpus]], which locates
  * third-party artifacts that are fetched and never written. These fixtures sit
  * under [[NetworkFixtureCorpus]], a separate root holding material this project
  * authors. Threading the second root through the first harness would make every
  * published corpus collapse whenever the authored tree is absent, so the two
  * roots keep their own failure modes -- which is the arrangement
  * `org.fukuii.consensus.pow.certification.ClassicDifficultyCorpus` already uses
  * for the same reason.
  *
  * ==What a fixture here is worth, which is not what a published one is worth==
  *
  * The published corpora are evidence from outside this project. These are
  * authored, so agreement is worth what the fixture's own oracle is worth.
  * Every file here names `core-geth` at a stated build as that oracle -- **the
  * modernized fork, `white-b0x/core-geth`, a client this project has worked
  * on, rather than `ethereumclassic/core-geth`, whose newest tag is
  * `v1.12.20` and which carries no such version.** So agreement is between two
  * implementations that are not independent of one another, and the tree
  * states TWO oracle chains rather than one, so the strength is uneven and a
  * reader has to know which chain a case sits on.
  *
  * **Thirty-seven of the forty-five are filled through that client's `t8n` at
  * each upgrade's rules and record a second pass through its own `statetest`
  * runner. The remaining eight are filled through `statetest` directly, and
  * seven of those record no second pass at all** -- correctly, because for them
  * the filling instrument and the verifying instrument would be the same one.
  *
  * **Both chains name one build, so a second pass is a second RUNNER and never
  * a second implementation.** What the pair rules out is a fault in either
  * runner's own accounting; what it cannot rule out is a rule that build reads
  * wrongly, which both runners would then read wrongly together.
  *
  * **The second pass does not reach every case for a second reason, and the
  * corpus says so.** A geth-family state-test runner builds its message from
  * the `transaction` object's stated sender and never validates a signature, so
  * it cannot express a transaction being refused. Every case asserting a
  * refusal rests on the first oracle alone.
  *
  * ==A label names a rule set; the height is what resolves it==
  *
  * A file here states an expectation under each of this chain's upgrade names,
  * so one file answers a different question per label asked -- the shape the
  * legacy published tier already has, and the reason a report is keyed by both
  * the tree and the label.
  *
  * The rules are taken from the registry at a height stated here as a literal,
  * never read back from the schedule. A harness asking a schedule where a fork
  * begins and then asking the same schedule what runs there is true of every
  * schedule and certifies nothing; the literal is what makes the activation an
  * external fact the run can disagree with.
  */
object ClassicStateCorpus:

  /** The tree these fixtures sit in, under the network corpus root. */
  private def directory(under: Path): Path =
    NetworkFixtureCorpus.classicMainnet(under).resolve("state")

  /** The label Die Hard's expectations are filed under.
    *
    * The corpus's own spelling, which is what the reader dispatches on. Asking
    * for a name this chain's fixtures do not use would find nothing and report
    * every file as stating no expectation -- a silence indistinguishable from
    * agreement.
    */
  val DieHardFork: String = "ETC_DieHard"

  /** The name a report of Die Hard's rules over this tree carries. */
  val DieHardCorpus: String = "fukuii-tests ethereumclassic/mainnet state at Die Hard"

  /** The height this harness believes Die Hard begins at.
    *
    * `ethereumclassic/core-geth` @ `4185df450`'s `ClassicChainConfig` carries
    * `EIP155Block` and `EIP160FBlock` at 3,000,000, and ECIP-1066 files EIP-155
    * and EIP-160 under that row. Stated as a literal for the reason above.
    */
  private[certification] val DieHardStarts: Long = 3000000L

  /** The height the fork before it begins at, carried so a control can resolve
    * Die Hard's expectations under the previous fork's rules.
    *
    * Same source, `EIP150Block`. It is a control input rather than a corpus of
    * its own: a tier whose expectations are satisfied by the rules of the fork
    * below it is not evidence about the fork it is named for.
    */
  private[certification] val GasRepriceStarts: Long = 2500000L

  private def schedule: Option[UpgradeSchedule] =
    KnownNetworks.registry.toOption.flatMap(_.at(ethereumclassic.Mainnet.network.chainId))

  /** This tree read under one label, resolved at one height, with the resolved
    * rules put through `change` before anything runs.
    *
    * @param change
    *   how the rules are altered before the run. A parameter so that a negative
    *   control can feed a deliberately wrong rule set and watch the same runner
    *   refuse it: a harness whose only ever input is the answer it expects has
    *   no reachable failing state, and reports an agreement it never tested for.
    *   It is also the differential instrument -- removing one proposal and
    *   counting the cases that answer differently is how this tier's coverage
    *   OF that proposal is measured, because what a corpus mentions and what it
    *   can decide are different claims.
    */
  private[certification] def reportAt(
      name: String,
      fork: String,
      height: Long,
      change: UpgradeRules => UpgradeRules
  ): Option[CorpusReport] =
    for
      root <- NetworkFixtureCorpus.root
      resolved <- schedule
    yield
      // Taken from the same schedule the rules are taken from, so the pair
      // cannot drift into asking one network's rules as though it were another's.
      val chainId = resolved.network.chainId
      val rules = change(resolved.at(UInt64.fromBits(height), UInt64.Zero))
      assemble(name, directory(root), fork, chainId, rules)

  /** Die Hard's rules over this tree, as the run every count is read from. */
  lazy val dieHard: Option[CorpusReport] =
    reportAt(DieHardCorpus, DieHardFork, DieHardStarts, identity)

  /** The label Atlantis's expectations are filed under.
    *
    * The corpus's own spelling, for [[DieHardFork]]'s reason: a name this
    * chain's fixtures do not use would find nothing and report every file as
    * stating no expectation, which is a silence indistinguishable from
    * agreement.
    */
  val AtlantisFork: String = "ETC_Atlantis"

  /** The name a report of Atlantis's rules over this tree carries. */
  val AtlantisCorpus: String = "fukuii-tests ethereumclassic/mainnet state at Atlantis"

  /** The height this harness believes Atlantis begins at.
    *
    * `ethereumclassic/core-geth` @ `4185df450` carries this network's ten
    * transitions for that upgrade at `big.NewInt(8772000)` in
    * `params/config_classic.go:56-67`, and `besu-eth/besu-etc` @ `eb4248c997`
    * states it as `"atlantisBlock": 8772000`. Stated as a literal for the
    * reason above.
    */
  private[certification] val AtlantisStarts: Long = 8772000L

  /** The height the fork before it begins at, carried so a control can resolve
    * Atlantis's expectations under the previous fork's rules.
    *
    * `DisposalBlock: big.NewInt(5900000)` in the same file. It is a control
    * input rather than a corpus of its own, for [[GasRepriceStarts]]'s reason.
    */
  private[certification] val DefuseStarts: Long = 5900000L

  /** Atlantis's rules over this tree, as the run every count is read from. */
  lazy val atlantis: Option[CorpusReport] =
    reportAt(AtlantisCorpus, AtlantisFork, AtlantisStarts, identity)

  /** The label Agharta's expectations are filed under.
    *
    * The corpus's own spelling, for [[DieHardFork]]'s reason: a name this
    * chain's fixtures do not use would find nothing and report every file as
    * stating no expectation, which is a silence indistinguishable from
    * agreement.
    */
  val AghartaFork: String = "ETC_Agharta"

  /** The name a report of Agharta's rules over this tree carries. */
  val AghartaCorpus: String = "fukuii-tests ethereumclassic/mainnet state at Agharta"

  /** The height this harness believes Agharta begins at.
    *
    * `ethereumclassic/core-geth` @ `4185df450` sets this network's three
    * transitions for that upgrade at `big.NewInt(9573000)` in
    * `params/config_classic.go:70-72`, and ECIP-1056 names the height itself.
    * `org.fukuii.chainspec.networks.ethereumclassic.Mainnet` carries the full
    * sourcing, including why one tabulation of this network's schedule states
    * a figure ten thousand blocks higher and is not a source for it. Stated as
    * a literal for the reason above.
    */
  private[certification] val AghartaStarts: Long = 9573000L

  /** Agharta's rules over this tree, as the run every count is read from. */
  lazy val agharta: Option[CorpusReport] =
    reportAt(AghartaCorpus, AghartaFork, AghartaStarts, identity)

  /** The label Phoenix's expectations are filed under.
    *
    * The corpus's own spelling, for [[DieHardFork]]'s reason: a name this
    * chain's fixtures do not use would find nothing and report every file as
    * stating no expectation, which is a silence indistinguishable from
    * agreement.
    */
  val PhoenixFork: String = "ETC_Phoenix"

  /** The name a report of Phoenix's rules over this tree carries. */
  val PhoenixCorpus: String = "fukuii-tests ethereumclassic/mainnet state at Phoenix"

  /** The height this harness believes Phoenix begins at.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states this network's six
    * transitions for that upgrade at `big.NewInt(10_500_839)` in
    * `params/config_classic.go:78-83`, and `besu-eth/besu-etc` @ `eb4248c997`
    * states it as `"phoenixBlock": 10500839`. ECIP-1088 names the figure
    * itself, and `org.fukuii.chainspec.networks.ethereumclassic.Mainnet`
    * carries the full sourcing. Stated as a literal for the reason above.
    */
  private[certification] val PhoenixStarts: Long = 10500839L

  /** Phoenix's rules over this tree, as the run every count is read from. */
  lazy val phoenix: Option[CorpusReport] =
    reportAt(PhoenixCorpus, PhoenixFork, PhoenixStarts, identity)

  private def assemble(
      name: String,
      under: Path,
      fork: String,
      chainId: UInt64,
      rules: UpgradeRules
  ): CorpusReport =
    val files = FixtureCorpus.jsonFilesUnder(under)
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
          val run = contents.fixtures.map { fixture =>
            CertificationCorpora.outcomeOf(fixture.name)(StateFixtureRunner.run(fixture, chainId, rules))
          }
          skipped ++ run
    }
    CorpusReport(name, files.length, outcomes)
