package org.fukuii.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereumclassic
import org.fukuii.consensus.{BlockFault, BlockValidator, BlockVerdict, EngineRule, HeaderFault, Resolved, RuleNotRun}
import org.fukuii.evm.EvmFixtures
import org.fukuii.trie.Trie
import org.fukuii.types.{Block, BlockBody, BlockHeader, BlockNonce, Bloom, Seal}

/** What a block answers once this engine's header rules have run over it: the
  * difficulty decided, and the seal named or absent as the network declares.
  *
  * ==A block no other rule this build runs refuses==
  *
  * The pair below is a parent and its successor that every shared header rule
  * accepts under a proof-of-work rule set: consecutive numbers, a later
  * timestamp, an unchanged gas limit, no fee market, an empty body committed to
  * as empty. The successor states the difficulty its parent requires, worked
  * by hand: the rule set adjusts by EIP-100, whose multiplier over a
  * thirteen-second gap with no parent ommers is `1 - 13 / 9 = 0`, so the
  * figure is the parent's own. Nothing about the pair is a mined block's, and
  * the seal is never read.
  *
  * The block is run, and compared, before the rules are named. Its state root
  * is answered by a stand-in agreeing with the header, because what the
  * engine's settlement writes into state is `EthashEngineSpec`'s subject and not
  * this one's. `EthashEngineHeaderPropSpec` holds the header rules' answers
  * across rule sets and seal engines.
  */
class EthashEngineHeaderSpec extends AnyFlatSpec:

  private val Classic: UpgradeRules = ethereumclassic.Upgrades.spiral

  private val engine: EthashEngine = EthashEngine(Some(EthashEngine.ClassicEraLength))

  private val withoutProof: EthashEngine =
    EthashEngine(Some(EthashEngine.ClassicEraLength), sealEngine = SealEngine.NoProof)

  /** What the state root is answered as, and what both headers state. */
  private val StandInRoot: Hash = EvmFixtures.hash(2)

  /** The parent's difficulty, and under the gap below the successor's. */
  private val Difficulty: UInt256 = UInt256.fromLong(8388608L).fold(error => fail(error.toString), identity)

  private val parent: BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(1),
      ommersHash = BlockHeader.EmptyOmmersHash,
      beneficiary = EvmFixtures.address(0x33),
      stateRoot = StandInRoot,
      transactionsRoot = Trie.EmptyRoot,
      receiptsRoot = Trie.EmptyRoot,
      logsBloom = Bloom.Empty,
      difficulty = Difficulty,
      number = UInt64.fromBits(100L),
      gasLimit = UInt64.fromBits(8000000L),
      gasUsed = UInt64.Zero,
      timestamp = UInt64.fromBits(1000L),
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(EvmFixtures.hash(0), BlockNonce.Zero),
      tail = None
    )

  private val successor: BlockHeader =
    parent.copy(parentHash = parent.hash, number = UInt64.fromBits(101L), timestamp = UInt64.fromBits(1013L))

  /** Unread: the block carries no transaction whose signature names a chain. */
  private val NoChain: UInt64 = UInt64.Zero

  private def validated(header: BlockHeader, under: EthashEngine): BlockVerdict =
    BlockValidator.validate(
      block = Block(header, BlockBody(Seq.empty, Seq.empty, None)),
      rules = Classic,
      parent = Resolved(parent, Classic),
      engine = under,
      world = new EvmFixtures.MapWorldState,
      destroyAccount = _ => (),
      stateRoot = () => StandInRoot,
      blockHashAt = _ => EvmFixtures.hash(0),
      chainId = NoChain
    )

  private def isValid(verdict: BlockVerdict): Boolean = verdict match
    case BlockVerdict.Valid(_) => true
    case _                     => false

  "a block stating the difficulty its parent requires" should "be undecided, naming the seal alone" in
    assert(
      validated(successor, engine) == BlockVerdict.Undecided(RuleNotRun.EngineRules(Set(EngineRule.Seal))),
      "the difficulty ran and accepted the block, and no cache reaches header validation, so the seal is all that is left"
    )

  it should "be valid on a network declaring no proof" in
    assert(
      isValid(validated(successor, withoutProof)),
      "a chain sealed without proof states no seal rule, so a block every rule accepts is valid"
    )

  "a block stating another difficulty" should "be refused, naming the figure it stated and the one its parent requires" in {
    val stated = UInt256.fromLong(8388609L).fold(error => fail(error.toString), identity)
    assert(
      validated(successor.copy(difficulty = stated), withoutProof) ==
        BlockVerdict.Invalid(BlockFault.Header(HeaderFault.DifficultyMismatch(stated, Difficulty))),
      "declaring no proof exempts the seal and never the difficulty"
    )
  }

  "a header carrying 33 bytes of extra data" should "be refused by the bound this engine inherits" in
    assert(
      engine.validateHeader(
        Resolved(successor.copy(extraData = Bytes.fromIArray(IArray.fill(33)(0x2a.toByte))), Classic),
        Resolved(parent, Classic)
      ) == Left(HeaderFault.ExtraDataAboveLimit(33, 32)),
      "a rule the engine runs refusing the header comes before any rule it names as unrun"
    )
