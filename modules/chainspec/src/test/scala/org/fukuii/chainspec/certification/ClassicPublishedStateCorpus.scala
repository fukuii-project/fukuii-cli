package org.fukuii.chainspec.certification

import org.fukuii.evm.fixtures.*

import java.nio.file.Path

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.{UpgradeRules, UpgradeSchedule}
import org.fukuii.chainspec.networks.{KnownNetworks, ethereumclassic}

/** Ethereum Classic's own published state tier, run at that chain's schedule.
  *
  * ==Why this is a third harness rather than rows in either of the other two==
  *
  * [[ClassicStateCorpus]] reads material this project authors, under a root of
  * its own. [[CertificationCorpora]] reads the two published trees whose
  * directories each answer for one fork. This tree is published like the
  * second and keyed like the first -- one directory answers under every label
  * this chain carries -- and it is registered a subdirectory at a time rather
  * than whole, so its own root would collapse the published census whenever
  * this tree were absent. Keeping the three separate keeps three failure modes
  * separate.
  *
  * ==What a fixture here is worth, and why it is not substitutable==
  *
  * Every entry the registered directories state at this label is signed under
  * EIP-155 naming chain 61 -- 2515 of 2515, decoded from the published
  * `txbytes` rather than read off the `transaction` object, with the decoder
  * calibrated on two structurally different known answers in one file of this
  * tree (`stChainId/chainId.json`, whose entry at this label carries `v = 157`
  * and whose entry at `Frontier` carries `v = 27`). **A tier signed this way is
  * satisfiable by no other network's rules**, which is what a tier whose
  * transactions name no chain cannot claim: an unprotected corpus is valid
  * everywhere and so decides nothing about which chain it was filled for.
  *
  * That claim is scoped to the ten directories registered below and to this
  * label. It says nothing about the rest of the tree, and nothing about the
  * labels this tier does not read.
  *
  * ==The oracle, and where the chain stops==
  *
  * `etclabscore/tests-etc` @ `06ec708ea7f3707826142a97a94b61d40c920696`. 464 of
  * the 465 files read here record `evmetc version
  * 1.12.9-unstable-550af1e1-20221005` as their filling client in
  * `_info.filling-rpc-server`, and `550af1e1807cad22be03ab19dbb304d8ff672d3c`
  * is a commit on `ethereumclassic/core-geth`, contained in that tree's
  * `v1.12.10` and later tags -- an ancestry test that returns NO in the reverse
  * direction, so it discriminates. **So the expectation a case states is what an
  * Ethereum Classic client produced at Ethereum Classic's own rules.**
  *
  * **The exception is stated because a claim about every file is not a claim
  * about most of them.** `stPreCompiledContracts2/CallEcrecover_Overflow.json`
  * names a go-ethereum build instead. It carries an expectation at one label
  * only and none at this one, so it contributes no entry this tier runs and is
  * one of the nine the certification spec already names as silent here. **Every
  * entry that EXECUTES has the client above as its oracle**; that is the claim,
  * and it is narrower than the one about files.
  *
  * That matters for what the tree's generator does. `makeetc.sh` at the ref
  * above rewrites fork labels in the filler sources -- `Istanbul` becomes this
  * label -- and then the tree is filled from those sources. **A rewritten label
  * selects which rule set the filling client is asked to execute; it cannot
  * transplant another network's answer, because no other network's answer is
  * copied.** That is the whole of why a relabelled filler is admissible here,
  * and it is what the recorded filling client establishes.
  *
  * **A second mechanism sits beside the rename and is narrower than it reads.**
  * The generator also overlays hand-written fillers from an `src-etc` tree onto
  * the upstream ones, by recursive copy into seven named filler directories --
  * so it replaces individual FILES and not directories, and a directory it
  * names keeps every upstream file the overlay does not cover. Twenty-four
  * files are overlaid in all. Of the directories registered here it reaches
  * three, and inside those three it covers 15 files of 22 in `stRefundTest`,
  * one of two in `stChainId` and one of 28 in `stSStoreTest` -- **17 of the 465
  * files read here.** So all but a fraction of this tier is relabelled rather
  * than hand-written, which is why the argument above rests on the filling
  * client and not on the overlay.
  *
  * **A pass establishes that this build and that one agree. It does not
  * establish that either is right**, and the independence is one-sided: this
  * oracle predates this build and shares nothing with it, where the tree
  * [[ClassicStateCorpus]] reads names a client this project has worked on.
  *
  * ==A label names a rule set; the height is what resolves it==
  *
  * The rules are taken from the registry at a height stated here as a literal,
  * never read back from the schedule, for the reason [[ClassicStateCorpus]]
  * gives: a harness asking a schedule where a fork begins and then asking the
  * same schedule what runs there is true of every schedule and certifies
  * nothing.
  */
object ClassicPublishedStateCorpus:

  /** The subdirectories of this tree registered at this label.
    *
    * ==Ten of the sixty, and the selection is by target rather than by size==
    *
    * Every one of this upgrade's six proposals is the direct subject of at
    * least one of these: the chain-identifier operation, the executing
    * account's own balance, net-metered storage and its refund schedule, the
    * compression and curve natives, and the two state reads whose price moved.
    * The rest of the tree is registered by no phase here, and the largest
    * single directory it holds is registered by none -- what a directory costs
    * to run is attributable only if it lands on its own.
    *
    * **Stated as a literal list rather than as a listing of the tree**, so a
    * directory appearing upstream does not silently join this tier and change
    * every count below without an edit here.
    */
  val PhaseOneDirectories: Vector[String] =
    Vector(
      "stChainId",
      "stExtCodeHash",
      "stPreCompiledContracts",
      "stPreCompiledContracts2",
      "stRefundTest",
      "stSLoadTest",
      "stSStoreTest",
      "stSelfBalance",
      "stZeroKnowledge",
      "stZeroKnowledge2"
    )

  /** The label Phoenix's expectations are filed under in this tree.
    *
    * The tree's own spelling, which is what the reader dispatches on. Asking
    * for a name it does not use would find nothing and report every file as
    * stating no expectation -- a silence indistinguishable from agreement.
    */
  val PhoenixFork: String = "ETC_Phoenix"

  /** The name a report of Phoenix's rules over these directories carries. */
  val PhoenixCorpus: String = "tests-etc GeneralStateTests at Phoenix"

  /** The height this harness believes Phoenix begins at.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states this network's six
    * transitions for that upgrade at `big.NewInt(10_500_839)` in
    * `params/config_classic.go:78-83`, and `besu-eth/besu-etc` @ `eb4248c997`
    * states it as `"phoenixBlock": 10500839`. Stated as a literal for the
    * reason above.
    */
  private[certification] val PhoenixStarts: Long = 10500839L

  /** The height the fork before it begins at, carried so a control can resolve
    * this label's expectations under the previous fork's rules.
    *
    * `ethereumclassic/core-geth` @ `4185df450` sets that upgrade's three
    * transitions at `big.NewInt(9573000)` in `params/config_classic.go:70-72`.
    * It is a control input rather than a corpus of its own: a tier whose
    * expectations are satisfied by the rules of the fork below it is not
    * evidence about the fork it is named for.
    */
  private[certification] val AghartaStarts: Long = 9573000L

  private def schedule: Option[UpgradeSchedule] =
    KnownNetworks.registry.toOption.flatMap(_.at(ethereumclassic.Mainnet.network.chainId))

  /** These directories read under one label, resolved at one height, with the
    * resolved rules put through `change` before anything runs.
    *
    * @param change
    *   how the rules are altered before the run. A parameter so that a control
    *   can feed a deliberately wrong rule set and watch the same runner refuse
    *   it: a harness whose only ever input is the answer it expects has no
    *   reachable failing state, and reports an agreement it never tested for.
    */
  private[certification] def reportAt(
      name: String,
      fork: String,
      height: Long,
      change: UpgradeRules => UpgradeRules
  ): Option[CorpusReport] =
    for
      root <- FixtureCorpus.root
      resolved <- schedule
    yield
      // Taken from the same schedule the rules are taken from, so the pair
      // cannot drift into asking one network's rules as though it were another's.
      val chainId = resolved.network.chainId
      val rules = change(resolved.at(UInt64.fromBits(height), UInt64.Zero))
      assemble(name, FixtureCorpus.classicPublished(root), fork, chainId, rules)

  /** Phoenix's rules over these directories, as the run every count is read
    * from.
    */
  lazy val phoenix: Option[CorpusReport] =
    reportAt(PhoenixCorpus, PhoenixFork, PhoenixStarts, identity)

  /** Every `.json` under the registered subdirectories, in the order the tree
    * is walked, so two runs report the same thing.
    *
    * A directory the tree does not carry contributes no files rather than
    * failing, which is why the file count is asserted as a literal beside the
    * run: a mistyped name here would otherwise narrow the tier silently.
    */
  private def files(under: Path): Vector[Path] =
    PhaseOneDirectories.flatMap(directory => FixtureCorpus.jsonFilesUnder(under.resolve(directory)))

  private def assemble(
      name: String,
      under: Path,
      fork: String,
      chainId: UInt64,
      rules: UpgradeRules
  ): CorpusReport =
    val read = files(under)
    val outcomes = read.flatMap { file =>
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
    CorpusReport(name, read.length, outcomes)
