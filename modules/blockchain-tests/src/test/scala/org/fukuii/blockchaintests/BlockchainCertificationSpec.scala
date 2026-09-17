package org.fukuii.blockchaintests

import java.nio.file.Path

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256}
import org.fukuii.chainspec.{ConsensusRules, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.consensus.{ConsensusEngine, EngineRule, HeaderFault, Resolved, RuleNotRun}
import org.fukuii.consensus.pow.EthashEngine
import org.fukuii.evm.{EvmFixtures, WorldState}
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureCorpus}
import org.fukuii.rlp.RlpCodec
import org.fukuii.types.{Block, BlockHeader, Withdrawal}

/** The calibration the published block tier's pinned counts depend on.
  *
  * ==Run rather than recorded==
  *
  * Every count `BlockchainCertificationPropSpec` pins is satisfied by a runner
  * that agrees, and a runner that had stopped comparing would report the same
  * counts. So published cases, read from their labels by the same reader, are
  * each run with one defect seeded in a different comparison the runner makes,
  * and the same report is required to count every one as a divergence and none
  * as anything else. `BlockchainRunnerSpec` and `BlockchainRunnerPropSpec` hold
  * the same comparisons with the reason each must report.
  *
  * ==A case per corpus the comparisons first reach==
  *
  * The head, the post-state, a refusal's name and its decoded form are reached
  * by `for_paris`'s cases; a blob schedule and a block this build cannot decode
  * by a `for_cancun` case stating both; a refusal with blocks after it by
  * `bcInvalidHeaderTest`'s `badTimestamp`, the one published case of that
  * shape; the difficulty rule and a case's seal engine by a `for_frontier` case;
  * and whose names a refusal is stated in by one case each from the two
  * snapshots of `bcInvalidHeaderTest`, each run in the other's names.
  *
  * ==The two block rewards, each against its neighbor==
  *
  * No published case refuses a block for its reward, so a wrong figure would
  * show only as a state root. A `for_byzantium` case run under the reward the
  * fork before it pays, and a `for_constantinoplefix` case run under
  * Byzantium's, must each diverge on the state root, which is what shows the
  * published roots decide both figures.
  *
  * **A missing corpus fails rather than cancels**, and every accessor below is a
  * `def` for the reason `EnginePayloadCertificationSpec` records: a `fail` run
  * from a `val` aborts the suite, which reads as tests removed.
  */
class BlockchainCertificationSpec extends AnyFlatSpec:

  private def root: Path = FixtureCorpus.root.getOrElse(fail("the published corpus was not found"))

  private def labelled(label: String): BlockchainLabel =
    BlockchainCorpus.label(label).getOrElse(fail("no label " + label))

  /** A published case from a named file under a label, as the corpus reader
    * decodes it.
    */
  private def fromCorpus(label: String, relative: String, name: String): BlockchainFixture =
    BlockchainCorpus
      .casesIn(root, labelled(label), relative)
      .fold(error => fail(error), identity)
      .fixtures
      .find(_.name == name)
      .getOrElse(fail("no case " + name + " in " + relative))

  private val Wrong: Hash = EvmFixtures.hash(0x5a)

  private type StatedSchedule = Map[String, StatedBlobEntry]

  private def names(stated: String*): ExpectedRejection = ExpectedRejection(stated.toSet)

  /** Every refusal a case states, with its names replaced. */
  private def restated(refusal: BlockchainFixture, replaced: ExpectedRejection): BlockchainFixture =
    refusal.copy(blocks = refusal.blocks.map(renamed(_, replaced)))

  /** The one refusal a case states at `index`, with its names replaced. */
  private def restatedAt(fixture: BlockchainFixture, index: Int, replaced: ExpectedRejection): BlockchainFixture =
    fixture.copy(blocks = fixture.blocks.updated(index, renamed(fixture.blocks(index), replaced)))

  private def renamed(block: StatedBlock, replaced: ExpectedRejection): StatedBlock = block match
    case StatedBlock.Invalid(rlp, _, decoded) => StatedBlock.Invalid(rlp, replaced, decoded)
    case valid @ StatedBlock.Valid(_, _)      => valid

  private def blockHashCase: BlockchainFixture =
    fromCorpus(
      "for_paris",
      "frontier/opcodes/blockhash/genesis_hash_available.json",
      PublishedParisCases.BlockHashCase
    )

  private def gasLimitCase: BlockchainFixture =
    fromCorpus(
      "for_paris",
      "frontier/validation/header/block_gas_limit_below_minimum.json",
      PublishedParisCases.GasLimitCase
    )

  /** A `for_cancun` case refusing a blob transaction that deploys, whose block
    * publishes no decoded form and whose `config` states a blob schedule.
    */
  private def contractCreationCase: BlockchainFixture =
    fromCorpus(
      "for_cancun",
      "cancun/eip4844_blobs/blob_txs/invalid_blob_tx_contract_creation.json",
      "tests/cancun/eip4844_blobs/test_blob_txs.py::test_invalid_blob_tx_contract_creation[fork_Cancun-blockchain_test-]"
    )

  private val TimestampsLabel: String = "bcInvalidHeaderTest at Cancun"

  /** `badTimestamp` at Cancun: ten blocks, refused at the fourth, sixth and
    * seventh, each for its timestamp, with valid blocks after them.
    */
  private def badTimestampCase: BlockchainFixture =
    fromCorpus(
      TimestampsLabel,
      "badTimestamp.json",
      "LegacyTests/Cancun/BlockchainTests/InvalidBlocks/bcInvalidHeaderTest/badTimestamp.json::badTimestamp_Cancun"
    )

  /** The published case at `label`'s fork whose one transaction reads the genesis
    * hash: two blocks, both accepted, each crediting its producer the fork's
    * reward.
    */
  private def withOneTransaction(label: String, fork: String): BlockchainFixture =
    fromCorpus(
      label,
      "frontier/opcodes/blockhash/genesis_hash_available.json",
      "tests/frontier/opcodes/test_blockhash.py::test_genesis_hash_available[fork_" + fork +
        "-blockchain_test-one_block_with_tx]"
    )

  private def frontierCase: BlockchainFixture = withOneTransaction("for_frontier", "Frontier")

  private def byzantiumCase: BlockchainFixture = withOneTransaction("for_byzantium", "Byzantium")

  private def constantinopleFixCase: BlockchainFixture =
    withOneTransaction("for_constantinoplefix", "ConstantinopleFix")

  private val OlderGasLimitLabel: String = "bcInvalidHeaderTest (Constantinople snapshot) at Frontier"

  private val GasLimitAtIstanbulLabel: String = "bcInvalidHeaderTest at Istanbul"

  /** A limit outside its parent's bound, which testeth names `InvalidGasLimit`. */
  private def olderGasLimitCase: BlockchainFixture =
    fromCorpus(OlderGasLimitLabel, "wrongGasLimit.json", "wrongGasLimit_Frontier")

  /** The same limit at Istanbul, which retesteth names `InvalidGasLimit2`. */
  private def gasLimitAtIstanbulCase: BlockchainFixture =
    fromCorpus(
      GasLimitAtIstanbulLabel,
      "wrongGasLimit.json",
      "LegacyTests/Cancun/BlockchainTests/InvalidBlocks/bcInvalidHeaderTest/wrongGasLimit.json::wrongGasLimit_Istanbul"
    )

  private def withBlobEntry(fixture: BlockchainFixture)(edit: StatedSchedule => StatedSchedule): BlockchainFixture =
    val config = fixture.config.getOrElse(fail("the case states no config"))
    val schedule = config.blobSchedule.getOrElse(fail("the case states no blob schedule"))
    fixture.copy(config = Some(config.copy(blobSchedule = Some(edit(schedule)))))

  private def cancunEntry(schedule: StatedSchedule): StatedBlobEntry =
    schedule.getOrElse("Cancun", fail("the case's blob schedule states no Cancun entry"))

  private def decodedBlock(rlp: Bytes): Block =
    RlpCodec.decodeFrom[Block](rlp.toIArray).fold(error => fail(error.toString), identity)

  /** `badTimestamp` with its first refused block replaced by the block after it
    * restated with a wrong state root: a refusal that only running the block
    * reaches, with blocks after it.
    */
  private def refusedAfterRunning(fixture: BlockchainFixture): BlockchainFixture =
    val follower = fixture.blocks(4) match
      case StatedBlock.Valid(rlp, _)    => rlp
      case StatedBlock.Invalid(_, _, _) => fail("badTimestamp's fifth block is one it accepts")
    val block = decodedBlock(follower)
    val wrongRoot = block.copy(header = block.header.copy(stateRoot = Wrong))
    val encoded = Bytes.fromIArray(RlpCodec.encodeTo(wrongRoot))
    fixture.copy(blocks =
      fixture.blocks.updated(3, StatedBlock.Invalid(encoded, names("InvalidStateRoot"), Some(wrongRoot.hash)))
    )

  /** A case whose last block states one more than the difficulty its parent
    * requires, restated as the block the case accepts and ends on.
    */
  private def restatedDifficulty(fixture: BlockchainFixture): BlockchainFixture =
    val last = fixture.blocks.length - 1
    val stated = fixture.blocks(last) match
      case StatedBlock.Valid(rlp, _)    => decodedBlock(rlp)
      case StatedBlock.Invalid(_, _, _) => fail("the case's last block is one it refuses")
    val raised = UInt256.fromBigInt(stated.header.difficulty.toBigInt + 1).fold(error => fail(error.toString), identity)
    val restatedBlock = stated.copy(header = stated.header.copy(difficulty = raised))
    fixture.copy(
      blocks = fixture.blocks.updated(
        last,
        StatedBlock.Valid(Bytes.fromIArray(RlpCodec.encodeTo(restatedBlock)), restatedBlock.hash)
      ),
      lastBlockHash = restatedBlock.hash
    )

  /** The engine each network runs, with the block reward every rule set resolves
    * replaced by `reward`.
    */
  private def rewarding(reward: UInt256): String => Either[String, FixtureNetwork] =
    name =>
      FixtureNetworks.named(name).map { network =>
        val engine = network.engine
        network.copy(engine = new ConsensusEngine:
          override def rulesFrom(rules: UpgradeRules): UpgradeRules =
            val resolved = engine.rulesFrom(rules)
            resolved.copy(consensus = resolved.consensus.copy(blockReward = reward))
          override def settlement(
              rules: ConsensusRules,
              beneficiary: Address,
              number: BigInt,
              ommers: Seq[BlockHeader]
          ): WorldState => Unit = engine.settlement(rules, beneficiary, number, ommers)
          override def validateHeader(block: Resolved, parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
            engine.validateHeader(block, parent)
          override def processWithdrawals(
              withdrawals: Seq[Withdrawal],
              destroyAccount: Address => Unit
          ): WorldState => Unit = engine.processWithdrawals(withdrawals, destroyAccount))
      }

  private def controls: BlockchainReport =
    val chain = blockHashCase
    val refusal = gasLimitCase
    val creation = contractCreationCase
    val timestamps = badTimestampCase
    val (listed, account) = chain.postState.toVector.minBy(_._1.toHex)
    val (firstAccepted, firstHash) =
      chain.blocks.collectFirst { case StatedBlock.Valid(rlp, hash) => (rlp, hash) }.getOrElse(fail("no block"))
    val config = chain.config.getOrElse(fail("the case states no config"))
    val ids = FillingTool.ExecutionSpecs
    val seeded = Vector(
      ("a wrong last block hash", chain.copy(lastBlockHash = Wrong), ids),
      (
        "a wrong post-state account",
        chain.copy(postState = chain.postState.updated(listed, account.copy(balance = account.balance + 1))),
        ids
      ),
      (
        "a refusal under a name no vocabulary holds",
        restated(refusal, names("BlockException.NOT_A_PUBLISHED_NAME")),
        ids
      ),
      (
        "a refusal under a known name for another rule",
        restated(refusal, names("BlockException.INVALID_BASEFEE_PER_GAS")),
        ids
      ),
      (
        "a block the case refuses, which this build accepts",
        chain.copy(
          blocks =
            Vector(StatedBlock.Invalid(firstAccepted, names("BlockException.INVALID_GASLIMIT"), Some(firstHash))),
          lastBlockHash = chain.genesisHash,
          postState = chain.pre
        ),
        ids
      ),
      (
        "a refused block whose decoded form hashes otherwise",
        refusal.copy(blocks = refusal.blocks.map {
          case StatedBlock.Invalid(rlp, stated, _) => StatedBlock.Invalid(rlp, stated, Some(Wrong))
          case valid @ StatedBlock.Valid(_, _)     => valid
        }),
        ids
      ),
      (
        "a config key this runner does not compare",
        chain.copy(config = Some(config.copy(otherKeys = Vector("NOT_A_PUBLISHED_KEY")))),
        ids
      ),
      (
        "a blob schedule stating another target than its fork's rules",
        withBlobEntry(creation)(schedule =>
          schedule.updated("Cancun", cancunEntry(schedule).copy(target = cancunEntry(schedule).target.map(_ + 1)))
        ),
        ids
      ),
      (
        "a blob schedule naming a fork no rules are resolved for",
        withBlobEntry(creation)(schedule => schedule.updated("NotAPublishedFork", cancunEntry(schedule))),
        ids
      ),
      (
        "a key inside a blob schedule's entry this runner does not compare",
        withBlobEntry(creation)(schedule =>
          schedule.updated("Cancun", cancunEntry(schedule).copy(otherKeys = Vector("NOT_A_PUBLISHED_KEY")))
        ),
        ids
      ),
      (
        "a block whose body does not decode, stated under the name for a header that does not",
        restated(creation, names("BlockException.INCORRECT_BLOCK_FORMAT")),
        ids
      ),
      (
        "a block that does not decode, stated under a transaction's name alone",
        restated(creation, names("TransactionException.TYPE_3_TX_CONTRACT_CREATION")),
        ids
      ),
      (
        "a refusal with blocks after it, under a legacy name for another rule",
        restatedAt(timestamps, 3, names("InvalidNumber")),
        labelled(TimestampsLabel).filledBy
      ),
      (
        "a refusal with blocks after it, reached only by running its block",
        refusedAfterRunning(timestamps),
        labelled(TimestampsLabel).filledBy
      ),
      ("a block stating a difficulty other than its parent requires", restatedDifficulty(frontierCase), ids),
      ("a case stating a seal engine this runner does not run", frontierCase.copy(sealEngine = Some("Ethash")), ids),
      (
        "a refusal testeth names InvalidGasLimit for the bound, read in retesteth's names",
        olderGasLimitCase,
        FillingTool.Retesteth
      ),
      (
        "a refusal retesteth names InvalidGasLimit2 for the bound, read in testeth's names",
        gasLimitAtIstanbulCase,
        FillingTool.Testeth
      )
    )
    BlockchainReport(
      "controls",
      0,
      seeded.map((name, fixture, filledBy) =>
        BlockchainOutcome(name, StatedShape.of(fixture), BlockchainRunner.run(fixture, filledBy = filledBy))
      )
    )

  private def reasonsOf(result: BlockchainResult): Vector[String] = result.verdict match
    case BlockchainVerdict.Diverged(reasons) => reasons
    case _                                   => Vector.empty

  "the tier's calibration" should "count a divergence for every seeded defect in a published case" in {
    val report = controls
    val seeded = report.cases
    val diverged = report.diverged.length
    val agreed = report.agreed.length
    assert(seeded == 18 && diverged == 18 && agreed == 0, report.describe)
  }

  it should "agree with each published case the defects were seeded into, unseeded" in {
    val unseeded = Vector(
      (blockHashCase, FillingTool.ExecutionSpecs),
      (gasLimitCase, FillingTool.ExecutionSpecs),
      (contractCreationCase, FillingTool.ExecutionSpecs),
      (badTimestampCase, labelled(TimestampsLabel).filledBy),
      (frontierCase, FillingTool.ExecutionSpecs),
      (byzantiumCase, FillingTool.ExecutionSpecs),
      (constantinopleFixCase, FillingTool.ExecutionSpecs),
      (olderGasLimitCase, labelled(OlderGasLimitLabel).filledBy),
      (gasLimitAtIstanbulCase, labelled(GasLimitAtIstanbulLabel).filledBy)
    )
    val verdicts =
      unseeded.map((fixture, filledBy) => fixture.name -> BlockchainRunner.run(fixture, filledBy = filledBy).verdict)
    assert(verdicts.forall(_._2 == BlockchainVerdict.Agreed), verdicts.toString)
  }

  "a published Byzantium case, run under the reward Spurious Dragon pays," should "diverge on its state root" in {
    val result = BlockchainRunner.run(byzantiumCase, rewarding(ethereum.Upgrades.spuriousDragon.consensus.blockReward))
    assert(
      reasonsOf(result).exists(_.contains("StateRootMismatch")) && result.blocksAccepted == 0,
      "EIP-649's figure is decided by the state root this case publishes: " + result.toString
    )
  }

  "a published ConstantinopleFix case, run under the reward Byzantium pays," should "diverge on its state root" in {
    val result =
      BlockchainRunner.run(constantinopleFixCase, rewarding(ethereum.Upgrades.byzantium.consensus.blockReward))
    assert(
      reasonsOf(result).exists(_.contains("StateRootMismatch")) && result.blocksAccepted == 0,
      "EIP-1234's figure is decided by the state root this case publishes: " + result.toString
    )
  }

  "a published case before the merge, run under the engine that states a seal," should "be undecided at its first block, naming the seal" in {
    val stating: String => Either[String, FixtureNetwork] =
      name => FixtureNetworks.named(name).map(_.copy(engine = EthashEngine()))
    val result = BlockchainRunner.run(frontierCase, stating)
    assert(
      result.verdict == BlockchainVerdict.Undecided(0, RuleNotRun.EngineRules(Set(EngineRule.Seal))),
      "the no-proof configuration is what lets a block every other rule accepts be valid: " + result.toString
    )
  }
