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
  * authored, so agreement is worth what the fixture's own oracle is worth. Both
  * files this section was opened for name `core-geth` at a stated build, run
  * through that client's `t8n` at each upgrade's rules, and record a second
  * pass through that client's own `statetest` runner. That is an implementation
  * this project does not maintain answering independently of it, which is what
  * makes agreement a cross-check rather than one reading repeated.
  *
  * **The second pass does not reach every case, and the corpus says so.** A
  * geth-family state-test runner builds its message from the `transaction`
  * object's stated sender and never validates a signature, so it cannot express
  * a transaction being refused. Every case asserting a refusal rests on the
  * first oracle alone.
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
