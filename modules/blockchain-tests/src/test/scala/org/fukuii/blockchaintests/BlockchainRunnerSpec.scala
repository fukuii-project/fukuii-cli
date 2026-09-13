package org.fukuii.blockchaintests

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.consensus.{ConsensusEngine, EngineRule, HeaderFault, Resolved, RuleNotRun}
import org.fukuii.evm.{EvmFixtures, WorldState}
import org.fukuii.evm.fixtures.{ExpectedRejection, FixtureAccount, SkipReason}
import org.fukuii.rlp.RlpCodec
import org.fukuii.types.{Block, BlockHeader}

/** The runner over three published cases, and each way a case must fail to
  * agree.
  *
  * ==The agreements are the release's; the failures are one change each==
  *
  * The three cases are [[PublishedParisCases]]'s, run exactly as published: the
  * runner verifies every hash and root they state as it goes, so an agreement
  * here is this build reproducing values the filling tool produced. Every
  * failure below changes one member of one of those cases, or the engine it runs
  * under, and nothing else, and asserts the reason as well as the outcome -- a
  * runner that diverged on everything would satisfy a bare count, and could not
  * tell a seeded defect from an unrelated breakage.
  *
  * ==What the controls are for==
  *
  * A published label that agrees everywhere reports zero divergences, and a
  * runner that had stopped comparing would report the same zero. **Each control
  * is a comparison the runner makes, shown to refuse**: a head that is not the
  * case's, a world holding some other state, a refusal under a name no
  * vocabulary holds, a refusal under a known name for another rule, a block
  * accepted where the case refuses it, and a refused block with another block
  * after it.
  *
  * ==And the outcomes no published label reaches yet==
  *
  * A block left undecided, a refused block that does not decode, and a block
  * this build raises on all first appear in labels this runner has not run, and
  * the counts pinned there will be written from its own output. So each is
  * reached here first, through an engine standing in for a mechanism, and its
  * outcome is asserted before any label depends on it.
  */
class BlockchainRunnerSpec extends AnyFlatSpec:

  private def published(name: String): BlockchainFixture =
    PublishedParisCases.fixture(name).fold(error => fail(error), identity)

  private def blockHash: BlockchainFixture = published(PublishedParisCases.BlockHashCase)

  private def gasLimit: BlockchainFixture = published(PublishedParisCases.GasLimitCase)

  private def signature: BlockchainFixture = published(PublishedParisCases.SignatureCase)

  /** A hash no published value below takes. */
  private val Wrong: Hash = EvmFixtures.hash(0x5a)

  /** Bytes that decode to no block: a one-byte string where a block is a list. */
  private val Undecodable: Bytes = Bytes.fromIArray(IArray(0x01.toByte))

  private val GasLimitName: ExpectedRejection = ExpectedRejection(Set("BlockException.INVALID_GASLIMIT"))

  /** An engine whose mechanism states a seal rule it does not run. */
  private val leavesTheSealUnrun: ConsensusEngine = new ConsensusEngine:
    override def validateHeader(block: Resolved, parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
      super.validateHeader(block, parent).map(_ + EngineRule.Seal)

  /** An engine whose settlement raises on the second block and on no other. */
  private val raisesAtTheSecondBlock: ConsensusEngine = new ConsensusEngine:
    override def settlement(
        rules: ConsensusRules,
        beneficiary: Address,
        number: BigInt,
        ommers: Seq[BlockHeader]
    ): WorldState => Unit =
      if number == BigInt(2) then throw new IllegalStateException("a settlement that raises at block 2")
      else super.settlement(rules, beneficiary, number, ommers)

  /** The published networks, with `engine` running every one of them. */
  private def runningUnder(engine: ConsensusEngine): String => Either[String, FixtureNetwork] =
    name => FixtureNetworks.named(name).map(_.copy(engine = engine))

  private def reasonsOf(result: BlockchainResult): Vector[String] = result.verdict match
    case BlockchainVerdict.Diverged(reasons) => reasons
    case _                                   => Vector.empty

  /** Whether `result` diverged, and for a reason containing `fragment`. */
  private def divergedFor(result: BlockchainResult, fragment: String): Boolean =
    reasonsOf(result).exists(_.contains(fragment))

  private def agreed(result: BlockchainResult): Boolean = result.verdict == BlockchainVerdict.Agreed

  private def skippedNaming(result: BlockchainResult, fragment: String): Boolean = result.verdict match
    case BlockchainVerdict.Skipped(SkipReason.RuleNotBuilt(detail)) => detail.contains(fragment)
    case _                                                          => false

  private def hashOf(rlp: Bytes): Hash =
    RlpCodec.decodeFrom[Block](rlp.toIArray).fold(error => fail(error.toString), _.hash)

  /** The encoding of the refused block the gas-limit case states. */
  private def refusedEncoding: Bytes =
    gasLimit.blocks.collectFirst { case StatedBlock.Invalid(rlp, _, _) => rlp }.getOrElse(fail("no refused block"))

  /** The encoding of the first block the BLOCKHASH case accepts, and the hash
    * the case states for it.
    */
  private def firstAccepted: (Bytes, Hash) =
    blockHash.blocks.collectFirst { case StatedBlock.Valid(rlp, hash) => (rlp, hash) }.getOrElse(fail("no block"))

  /** The BLOCKHASH case's first block stated as refused over the untouched
    * genesis, which the case then ends at.
    */
  private def firstBlockRefused: BlockchainFixture =
    val (rlp, hash) = firstAccepted
    blockHash.copy(
      blocks = Vector(StatedBlock.Invalid(rlp, GasLimitName, Some(hash))),
      lastBlockHash = blockHash.genesisHash,
      postState = blockHash.pre
    )

  /** The gas-limit case with its one refused block restated. */
  private def withRefusal(rlp: Bytes, names: ExpectedRejection, decodedHash: Option[Hash]): BlockchainFixture =
    gasLimit.copy(blocks = Vector(StatedBlock.Invalid(rlp, names, decodedHash)))

  private def config: StatedConfig = blockHash.config.getOrElse(fail("the published case states no config"))

  /** The first account the case says the chain ends holding, one wei richer. */
  private def withRicherAccount(fixture: BlockchainFixture): BlockchainFixture =
    val (address, account) = fixture.postState.toVector.minBy(_._1.toHex)
    fixture.copy(postState = fixture.postState.updated(address, account.copy(balance = account.balance + 1)))

  "run" should "agree with a published chain whose last block reads the chain's hashes through BLOCKHASH" in {
    val result = BlockchainRunner.run(blockHash)
    assert(agreed(result) && result.blocksAccepted == 2 && result.refusalsAgreed == 0, result.toString)
  }

  it should "agree with a published block refused for its gas limit, under the name the case states" in {
    val result = BlockchainRunner.run(gasLimit)
    assert(agreed(result) && result.blocksAccepted == 0 && result.refusalsAgreed == 1, result.toString)
  }

  it should "agree with a published block refused for a transaction's signature, under the name the case states" in {
    val result = BlockchainRunner.run(signature)
    assert(agreed(result) && result.blocksAccepted == 0 && result.refusalsAgreed == 1, result.toString)
  }

  it should "count no refusal for a refused block it did not refuse under a stated name" in {
    val result = BlockchainRunner.run(
      withRefusal(refusedEncoding, ExpectedRejection(Set("A.NAME_NOT_HELD")), Some(hashOf(refusedEncoding)))
    )
    assert(result.refusalsAgreed == 0, result.toString)
  }

  it should "count the blocks accepted before a later comparison diverges" in {
    val result = BlockchainRunner.run(blockHash.copy(lastBlockHash = Wrong))
    assert(result.blocksAccepted == 2, "both blocks were accepted before the head was compared: " + result.toString)
  }

  "the genesis" should "diverge where its encoding hashes to some other hash than the case states" in {
    val result = BlockchainRunner.run(blockHash.copy(genesisHash = Wrong))
    assert(divergedFor(result, "the genesis hashes to"), result.toString)
  }

  it should "diverge where the pre-state seeds some other root than the genesis commits to" in {
    val extra = EvmFixtures.address(0x7e) -> FixtureAccount(BigInt(0), BigInt(1), Bytes.Empty, Map.empty)
    val result = BlockchainRunner.run(blockHash.copy(pre = blockHash.pre + extra))
    assert(divergedFor(result, "the pre-state seeds root"), result.toString)
  }

  it should "diverge where its encoding does not decode" in {
    val result = BlockchainRunner.run(blockHash.copy(genesisRlp = Undecodable))
    assert(divergedFor(result, "the genesis does not decode"), result.toString)
  }

  "a block the case accepts" should "diverge where its encoding hashes to some other hash than the case states" in {
    val (rlp, _) = firstAccepted
    val result =
      BlockchainRunner.run(blockHash.copy(blocks = blockHash.blocks.updated(0, StatedBlock.Valid(rlp, Wrong))))
    assert(divergedFor(result, "block 0 hashes to"), result.toString)
  }

  it should "diverge where its encoding does not decode" in {
    val restated = blockHash.blocks.updated(0, StatedBlock.Valid(Undecodable, Wrong))
    val result = BlockchainRunner.run(blockHash.copy(blocks = restated))
    assert(divergedFor(result, "block 0 does not decode"), result.toString)
  }

  it should "diverge where this build refuses it, naming the fault" in {
    // The gas-limit case's refused block, stated as one the case accepts. A
    // runner reporting this build's refusal as anything but a divergence would
    // be folding a refusal into agreement.
    val restated = Vector(StatedBlock.Valid(refusedEncoding, hashOf(refusedEncoding)))
    val result = BlockchainRunner.run(gasLimit.copy(blocks = restated))
    assert(divergedFor(result, "GasLimitOutOfBounds"), result.toString)
  }

  it should "be undecided, naming the block and the rule, where it reaches a rule its engine does not run" in {
    val result = BlockchainRunner.run(blockHash, runningUnder(leavesTheSealUnrun))
    val expected = BlockchainVerdict.Undecided(0, RuleNotRun.EngineRules(Set(EngineRule.Seal)))
    assert(result.verdict == expected && result.blocksAccepted == 0, result.toString)
  }

  "a block the case refuses" should "diverge where its encoding does not decode" in {
    val result = BlockchainRunner.run(withRefusal(Undecodable, GasLimitName, None))
    assert(divergedFor(result, "block 0 does not decode") && result.refusalsAgreed == 0, result.toString)
  }

  it should "diverge where its encoding hashes to some other hash than the decoded form the case publishes" in {
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, GasLimitName, Some(Wrong)))
    assert(divergedFor(result, "block 0 hashes to") && result.refusalsAgreed == 0, result.toString)
  }

  it should "diverge where this build decodes it and the case publishes no decoded form" in {
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, GasLimitName, None))
    assert(divergedFor(result, "publishes no decoded form of block 0"), result.toString)
  }

  it should "be undecided, naming the block and the rule, where it reaches a rule its engine does not run" in {
    val result = BlockchainRunner.run(firstBlockRefused, runningUnder(leavesTheSealUnrun))
    val expected = BlockchainVerdict.Undecided(0, RuleNotRun.EngineRules(Set(EngineRule.Seal)))
    assert(result.verdict == expected && result.refusalsAgreed == 0, result.toString)
  }

  "a case" should "be skipped, by name, where it names a network no rules are resolved for" in {
    val result = BlockchainRunner.run(blockHash.copy(network = "NotAPublishedNetwork"))
    assert(skippedNaming(result, "NotAPublishedNetwork"), result.toString)
  }

  it should "diverge where it states another chain id than its network runs as" in {
    val result = BlockchainRunner.run(blockHash.copy(config = Some(config.copy(chainId = Some(UInt64.fromBits(5L))))))
    assert(divergedFor(result, "chain id 5"), result.toString)
  }

  it should "diverge, naming the key, where its config states a key this runner does not compare" in {
    val result = BlockchainRunner.run(blockHash.copy(config = Some(config.copy(otherKeys = Vector("blobSchedule")))))
    assert(divergedFor(result, "config states blobSchedule"), result.toString)
  }

  it should "diverge where its config names another network than the case does" in {
    val result = BlockchainRunner.run(blockHash.copy(config = Some(config.copy(network = Some("Shanghai")))))
    assert(divergedFor(result, "names the network Shanghai"), result.toString)
  }

  it should "diverge, naming what was raised, where a block part-way through raises" in {
    val result = BlockchainRunner.run(blockHash, runningUnder(raisesAtTheSecondBlock))
    assert(
      divergedFor(result, "a settlement that raises at block 2") && result.blocksAccepted == 1,
      "the first block was accepted and the second raised: " + result.toString
    )
  }

  "the controls" should "diverge where the case's last block hash is not the head" in {
    val result = BlockchainRunner.run(blockHash.copy(lastBlockHash = Wrong))
    assert(divergedFor(result, "last block hash"), "the head comparison is what a wrong lastblockhash must reach")
  }

  it should "diverge where a case accepting no block states a last block hash other than its genesis" in {
    val result = BlockchainRunner.run(gasLimit.copy(lastBlockHash = Wrong))
    assert(divergedFor(result, "last block hash"), result.toString)
  }

  it should "diverge where the post-state states an account holding other than the chain's" in {
    val result = BlockchainRunner.run(withRicherAccount(blockHash))
    assert(divergedFor(result, "balance") && divergedFor(result, "root of the post-state"), result.toString)
  }

  it should "diverge where the post-state omits an account the chain created" in {
    // Only the root comparison reaches this: the account is in neither the
    // pre-state nor the post-state as stated, so a comparison over listed
    // accounts sees nothing wrong with an account it was never told about.
    val created = (blockHash.postState.keySet -- blockHash.pre.keySet).toVector.sortBy(_.toHex)
    val result = BlockchainRunner.run(blockHash.copy(postState = blockHash.postState -- created.take(1)))
    assert(created.nonEmpty && divergedFor(result, "root of the post-state"), created.toString + " " + result.toString)
  }

  it should "diverge where a refusal is stated under a name no vocabulary holds" in {
    val unpublished = ExpectedRejection(Set("BlockException.NOT_A_PUBLISHED_NAME"))
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, unpublished, Some(hashOf(refusedEncoding))))
    assert(divergedFor(result, "names none of BlockException.NOT_A_PUBLISHED_NAME"), result.toString)
  }

  it should "diverge where a refusal is stated under a known name for another rule" in {
    // The gas-limit case's block is refused for its gas limit, and is stated
    // here as refused for its base fee -- a name the vocabulary holds, so only a
    // comparison against the RULE the name maps to can refuse the agreement.
    val baseFee = ExpectedRejection(Set("BlockException.INVALID_BASEFEE_PER_GAS"))
    val result = BlockchainRunner.run(withRefusal(refusedEncoding, baseFee, Some(hashOf(refusedEncoding))))
    assert(
      divergedFor(result, "was refused as Header(GasLimitOutOfBounds") && !divergedFor(result, "names none of"),
      result.toString
    )
  }

  it should "diverge where a block the case refuses is accepted" in {
    val result = BlockchainRunner.run(firstBlockRefused)
    assert(divergedFor(result, "was accepted"), result.toString)
  }

  it should "diverge, loudly, where a block the case refuses is not its last" in {
    val refused = StatedBlock.Invalid(refusedEncoding, GasLimitName, Some(hashOf(refusedEncoding)))
    val result = BlockchainRunner.run(gasLimit.copy(blocks = Vector(refused, refused)))
    assert(divergedFor(result, "invalid with blocks after it"), result.toString)
  }
