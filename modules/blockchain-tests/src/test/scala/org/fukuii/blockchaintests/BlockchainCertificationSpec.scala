package org.fukuii.blockchaintests

import java.nio.file.Path

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, Hash}
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureCorpus}
import org.fukuii.rlp.RlpCodec
import org.fukuii.types.Block

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
  * shape.
  *
  * **A missing corpus fails rather than cancels**, and every accessor below is a
  * `def` for the reason `EnginePayloadCertificationSpec` records: a `fail` run
  * from a `val` aborts the suite, which reads as tests removed.
  */
class BlockchainCertificationSpec extends AnyFlatSpec:

  private def root: Path = FixtureCorpus.root.getOrElse(fail("the published corpus was not found"))

  /** A published case from a named file under a label, as the corpus reader
    * decodes it.
    */
  private def fromCorpus(label: String, relative: String, name: String): BlockchainFixture =
    val labelled = BlockchainCorpus.label(label).getOrElse(fail("no label " + label))
    BlockchainCorpus
      .casesIn(root, labelled, relative)
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

  /** `badTimestamp` at Cancun: ten blocks, refused at the fourth, sixth and
    * seventh, each for its timestamp, with valid blocks after them.
    */
  private def badTimestampCase: BlockchainFixture =
    fromCorpus(
      "bcInvalidHeaderTest at Cancun",
      "badTimestamp.json",
      "LegacyTests/Cancun/BlockchainTests/InvalidBlocks/bcInvalidHeaderTest/badTimestamp.json::badTimestamp_Cancun"
    )

  private def withBlobEntry(fixture: BlockchainFixture)(edit: StatedSchedule => StatedSchedule): BlockchainFixture =
    val config = fixture.config.getOrElse(fail("the case states no config"))
    val schedule = config.blobSchedule.getOrElse(fail("the case states no blob schedule"))
    fixture.copy(config = Some(config.copy(blobSchedule = Some(edit(schedule)))))

  private def cancunEntry(schedule: StatedSchedule): StatedBlobEntry =
    schedule.getOrElse("Cancun", fail("the case's blob schedule states no Cancun entry"))

  /** `badTimestamp` with its first refused block replaced by the block after it
    * restated with a wrong state root: a refusal that only running the block
    * reaches, with blocks after it.
    */
  private def refusedAfterRunning(fixture: BlockchainFixture): BlockchainFixture =
    val follower = fixture.blocks(4) match
      case StatedBlock.Valid(rlp, _)    => rlp
      case StatedBlock.Invalid(_, _, _) => fail("badTimestamp's fifth block is one it accepts")
    val block = RlpCodec.decodeFrom[Block](follower.toIArray).fold(error => fail(error.toString), identity)
    val wrongRoot = block.copy(header = block.header.copy(stateRoot = Wrong))
    val encoded = Bytes.fromIArray(RlpCodec.encodeTo(wrongRoot))
    fixture.copy(blocks =
      fixture.blocks.updated(3, StatedBlock.Invalid(encoded, names("InvalidStateRoot"), Some(wrongRoot.hash)))
    )

  private def controls: BlockchainReport =
    val chain = blockHashCase
    val refusal = gasLimitCase
    val creation = contractCreationCase
    val timestamps = badTimestampCase
    val (listed, account) = chain.postState.toVector.minBy(_._1.toHex)
    val (firstAccepted, firstHash) =
      chain.blocks.collectFirst { case StatedBlock.Valid(rlp, hash) => (rlp, hash) }.getOrElse(fail("no block"))
    val config = chain.config.getOrElse(fail("the case states no config"))
    val seeded = Vector(
      "a wrong last block hash" -> chain.copy(lastBlockHash = Wrong),
      "a wrong post-state account" ->
        chain.copy(postState = chain.postState.updated(listed, account.copy(balance = account.balance + 1))),
      "a refusal under a name no vocabulary holds" -> restated(refusal, names("BlockException.NOT_A_PUBLISHED_NAME")),
      "a refusal under a known name for another rule" ->
        restated(refusal, names("BlockException.INVALID_BASEFEE_PER_GAS")),
      "a block the case refuses, which this build accepts" ->
        chain.copy(
          blocks =
            Vector(StatedBlock.Invalid(firstAccepted, names("BlockException.INVALID_GASLIMIT"), Some(firstHash))),
          lastBlockHash = chain.genesisHash,
          postState = chain.pre
        ),
      "a refused block whose decoded form hashes otherwise" ->
        refusal.copy(blocks = refusal.blocks.map {
          case StatedBlock.Invalid(rlp, stated, _) => StatedBlock.Invalid(rlp, stated, Some(Wrong))
          case valid @ StatedBlock.Valid(_, _)     => valid
        }),
      "a config key this runner does not compare" ->
        chain.copy(config = Some(config.copy(otherKeys = Vector("NOT_A_PUBLISHED_KEY")))),
      "a blob schedule stating another target than its fork's rules" ->
        withBlobEntry(creation)(schedule =>
          schedule.updated("Cancun", cancunEntry(schedule).copy(target = cancunEntry(schedule).target.map(_ + 1)))
        ),
      "a blob schedule naming a fork no rules are resolved for" ->
        withBlobEntry(creation)(schedule => schedule.updated("NotAPublishedFork", cancunEntry(schedule))),
      "a key inside a blob schedule's entry this runner does not compare" ->
        withBlobEntry(creation)(schedule =>
          schedule.updated("Cancun", cancunEntry(schedule).copy(otherKeys = Vector("NOT_A_PUBLISHED_KEY")))
        ),
      "a block whose body does not decode, stated under the name for a header that does not" ->
        restated(creation, names("BlockException.INCORRECT_BLOCK_FORMAT")),
      "a block that does not decode, stated under a transaction's name alone" ->
        restated(creation, names("TransactionException.TYPE_3_TX_CONTRACT_CREATION")),
      "a refusal with blocks after it, under a legacy name for another rule" ->
        restatedAt(timestamps, 3, names("InvalidNumber")),
      "a refusal with blocks after it, reached only by running its block" -> refusedAfterRunning(timestamps)
    )
    BlockchainReport(
      "controls",
      0,
      seeded.map((name, fixture) => BlockchainOutcome(name, StatedShape.of(fixture), BlockchainRunner.run(fixture)))
    )

  "the tier's calibration" should "count a divergence for every seeded defect in a published case" in {
    val report = controls
    val seeded = report.cases
    val diverged = report.diverged.length
    val agreed = report.agreed.length
    assert(seeded == 14 && diverged == 14 && agreed == 0, report.describe)
  }

  it should "agree with each published case the defects were seeded into, unseeded" in {
    val unseeded = Vector(blockHashCase, gasLimitCase, contractCreationCase, badTimestampCase)
    val verdicts = unseeded.map(fixture => fixture.name -> BlockchainRunner.run(fixture).verdict)
    assert(verdicts.forall(_._2 == BlockchainVerdict.Agreed), verdicts.toString)
  }
