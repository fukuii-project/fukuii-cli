package org.fukuii.blockchaintests

import org.scalatest.Assertions

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.consensus.{ConsensusEngine, EngineRule, HeaderFault, Resolved}
import org.fukuii.evm.{EvmFixtures, WorldState}
import org.fukuii.evm.fixtures.{ExpectedRejection, SkipReason}
import org.fukuii.rlp.{Rlp, RlpCodec, RlpItem}
import org.fukuii.types.{Block, BlockHeader}

/** The published cases and the one-change variants `BlockchainRunnerSpec` and
  * `BlockchainRunnerPropSpec` run, and what each reads off a result.
  *
  * Every case is [[PublishedParisCases]]'s, or one of them with one member
  * changed, or run under an engine standing in for a mechanism -- the reasons
  * `BlockchainRunnerSpec` records.
  */
trait BlockchainRunnerCases extends Assertions:

  protected def published(name: String): BlockchainFixture =
    PublishedParisCases.fixture(name).fold(error => fail(error), identity)

  protected def blockHash: BlockchainFixture = published(PublishedParisCases.BlockHashCase)

  protected def gasLimit: BlockchainFixture = published(PublishedParisCases.GasLimitCase)

  protected def signature: BlockchainFixture = published(PublishedParisCases.SignatureCase)

  /** A hash no published value below takes. */
  protected val Wrong: Hash = EvmFixtures.hash(0x5a)

  /** Bytes that decode to no block: a one-byte string where a block is a list. */
  protected val Undecodable: Bytes = Bytes.fromIArray(IArray(0x01.toByte))

  /** Bytes that are not RLP at all: a list claiming two bytes and holding one. */
  protected val NotRlp: Bytes = Bytes.fromIArray(IArray(0xc2.toByte, 0x01.toByte))

  protected val GasLimitName: ExpectedRejection = ExpectedRejection(Set("BlockException.INVALID_GASLIMIT"))

  protected val StructuresName: ExpectedRejection = ExpectedRejection(Set("BlockException.RLP_STRUCTURES_ENCODING"))

  protected val FormatName: ExpectedRejection = ExpectedRejection(Set("BlockException.INCORRECT_BLOCK_FORMAT"))

  /** The blob parameters the fork that introduced them resolves. */
  protected val CancunBlobs: StatedBlobEntry =
    StatedBlobEntry(Some(BigInt(3)), Some(BigInt(6)), Some(BigInt(3338477)), Vector.empty)

  /** An engine whose mechanism states a seal rule it does not run. */
  protected val leavesTheSealUnrun: ConsensusEngine = new ConsensusEngine:
    override def validateHeader(block: Resolved, parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
      super.validateHeader(block, parent).map(_ + EngineRule.Seal)

  /** An engine refusing every header, naming a different reason each time it is
    * asked, so no second asking reproduces the first.
    */
  protected def refusingDifferentlyEachTime: ConsensusEngine = new ConsensusEngine:
    private var asked: Int = 0
    override def validateHeader(block: Resolved, parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
      asked += 1
      Left(HeaderFault.ExtraDataAboveLimit(asked, 0))

  /** An engine whose settlement raises on the second block and on no other. */
  protected val raisesAtTheSecondBlock: ConsensusEngine = new ConsensusEngine:
    override def settlement(
        rules: ConsensusRules,
        beneficiary: Address,
        number: BigInt,
        ommers: Seq[BlockHeader]
    ): WorldState => Unit =
      if number == BigInt(2) then throw new IllegalStateException("a settlement that raises at block 2")
      else super.settlement(rules, beneficiary, number, ommers)

  /** The published networks, with `engine` running every one of them. */
  protected def runningUnder(engine: ConsensusEngine): String => Either[String, FixtureNetwork] =
    name => FixtureNetworks.named(name).map(_.copy(engine = engine))

  protected def reasonsOf(result: BlockchainResult): Vector[String] = result.verdict match
    case BlockchainVerdict.Diverged(reasons) => reasons
    case _                                   => Vector.empty

  /** Whether `result` diverged, and for a reason containing `fragment`. */
  protected def divergedFor(result: BlockchainResult, fragment: String): Boolean =
    reasonsOf(result).exists(_.contains(fragment))

  protected def agreed(result: BlockchainResult): Boolean = result.verdict == BlockchainVerdict.Agreed

  protected def skippedNaming(result: BlockchainResult, fragment: String): Boolean = result.verdict match
    case BlockchainVerdict.Skipped(SkipReason.RuleNotBuilt(detail)) => detail.contains(fragment)
    case _                                                          => false

  protected def decodedBlock(rlp: Bytes): Block =
    RlpCodec.decodeFrom[Block](rlp.toIArray).fold(error => fail(error.toString), identity)

  protected def hashOf(rlp: Bytes): Hash = decodedBlock(rlp).hash

  protected def encoded(block: Block): Bytes = Bytes.fromIArray(RlpCodec.encodeTo(block))

  /** The encoding of the refused block the gas-limit case states. */
  protected def refusedEncoding: Bytes =
    gasLimit.blocks.collectFirst { case StatedBlock.Invalid(rlp, _, _) => rlp }.getOrElse(fail("no refused block"))

  /** The encodings of the blocks the BLOCKHASH case accepts, in order. */
  protected def accepted: Vector[(Bytes, Hash)] =
    blockHash.blocks.collect { case StatedBlock.Valid(rlp, hash) => (rlp, hash) }

  protected def firstAccepted: (Bytes, Hash) = accepted.headOption.getOrElse(fail("no block"))

  /** The BLOCKHASH case's first block stated as refused over the untouched
    * genesis, which the case then ends at.
    */
  protected def firstBlockRefused: BlockchainFixture =
    val (rlp, hash) = firstAccepted
    blockHash.copy(
      blocks = Vector(StatedBlock.Invalid(rlp, GasLimitName, Some(hash))),
      lastBlockHash = blockHash.genesisHash,
      postState = blockHash.pre
    )

  /** The gas-limit case with its one refused block restated. */
  protected def withRefusal(rlp: Bytes, names: ExpectedRejection, decodedHash: Option[Hash]): BlockchainFixture =
    gasLimit.copy(blocks = Vector(StatedBlock.Invalid(rlp, names, decodedHash)))

  /** The BLOCKHASH case with `refused` stated between its two blocks, refused
    * under `names` -- a refusal with a block after it, whose parent is the one
    * before it.
    */
  protected def refusingBetween(refused: Block, names: ExpectedRejection): BlockchainFixture =
    val stated = StatedBlock.Invalid(encoded(refused), names, Some(refused.hash))
    blockHash.copy(blocks = Vector(blockHash.blocks(0), stated, blockHash.blocks(1)))

  /** The BLOCKHASH case's second block, with `change` applied to its header. */
  protected def secondBlockWith(change: BlockHeader => BlockHeader): Block =
    val second = decodedBlock(accepted(1)._1)
    second.copy(header = change(second.header))

  protected def firstBlockHeader: BlockHeader = decodedBlock(firstAccepted._1).header

  /** The BLOCKHASH case's second block breaking two shared header rules: a
    * timestamp no later than its parent's, which runs first, and a gas limit
    * twice its parent's, which no step reaches.
    */
  protected def breakingTwoRules: Block =
    val doubled = UInt64
      .fromBigInt(firstBlockHeader.gasLimit.toBigInt * 2)
      .getOrElse(fail("a doubled gas limit a header cannot state"))
    secondBlockWith(_.copy(timestamp = firstBlockHeader.timestamp, gasLimit = doubled))

  /** `rlp` with its top-level items rewritten by `change`, as items rather than
    * as a block, so a shape no block has can be written.
    */
  protected def reshaped(rlp: Bytes)(change: Vector[RlpItem] => Vector[RlpItem]): Bytes =
    Rlp.decode(rlp.toIArray) match
      case Right(RlpItem.Sequence(items)) => Bytes.fromIArray(Rlp.encode(RlpItem.Sequence(change(items))))
      case other                          => fail("the refused block is not a sequence: " + other.toString)

  /** The refused block with two fields past any fork's header, which leaves a
    * header of a length no fork defines.
    */
  protected def headerOfNoForkShape: Bytes =
    reshaped(refusedEncoding) { items =>
      items(0) match
        case RlpItem.Sequence(fields) =>
          items.updated(0, RlpItem.Sequence(fields ++ Vector(RlpItem.Bytes(IArray.empty), RlpItem.Bytes(IArray.empty))))
        case other => fail("the refused block's header is not a sequence: " + other.toString)
    }

  /** The refused block with its transaction list replaced by a string, which
    * leaves a header that decodes inside a block that does not.
    */
  protected def bodyOfNoShape: Bytes =
    reshaped(refusedEncoding)(items => items.updated(1, RlpItem.Bytes(IArray(0x01.toByte))))

  protected def config: StatedConfig = blockHash.config.getOrElse(fail("the published case states no config"))

  protected def scheduling(entries: (String, StatedBlobEntry)*): BlockchainFixture =
    blockHash.copy(config = Some(config.copy(blobSchedule = Some(entries.toMap))))

  /** The first account the case says the chain ends holding, one wei richer. */
  protected def withRicherAccount(fixture: BlockchainFixture): BlockchainFixture =
    val (address, account) = fixture.postState.toVector.minBy(_._1.toHex)
    fixture.copy(postState = fixture.postState.updated(address, account.copy(balance = account.balance + 1)))
