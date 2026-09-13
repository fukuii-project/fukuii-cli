package org.fukuii.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereumclassic
import org.fukuii.consensus.{
  BlockValidator,
  BlockVerdict,
  EngineRule,
  HeaderFault,
  HeaderValidator,
  Resolved,
  RuleNotRun
}
import org.fukuii.evm.EvmFixtures
import org.fukuii.trie.Trie
import org.fukuii.types.{Block, BlockBody, BlockHeader, BlockNonce, Bloom, Seal}

/** What this engine's header rules answer, and which of them it does not run.
  *
  * ==A block no rule this build runs refuses, so the unrun ones are all that is
  * left to answer==
  *
  * The pair below is a parent and its successor that every shared header rule
  * accepts under a proof-of-work rule set: consecutive numbers, a later
  * timestamp, an unchanged gas limit, no fee market, an empty body committed to
  * as empty. Nothing about them is a mined block's, and nothing needs to be --
  * the difficulty and the seal are the two things this engine does not run, so
  * what they hold is never read.
  *
  * The block is run, and compared, before the rules are named. Its state root
  * is answered by a stand-in agreeing with the header, because what the
  * engine's settlement writes into state is `EthashEngineSpec`'s subject and not
  * this one's.
  */
class EthashEngineHeaderSpec extends AnyFlatSpec:

  private val Classic: UpgradeRules = ethereumclassic.Upgrades.spiral

  private val engine: EthashEngine = EthashEngine(Some(EthashEngine.ClassicEraLength))

  /** What the state root is answered as, and what both headers state. */
  private val StandInRoot: Hash = EvmFixtures.hash(2)

  private val parent: BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(1),
      ommersHash = HeaderValidator.EmptyOmmersHash,
      beneficiary = EvmFixtures.address(0x33),
      stateRoot = StandInRoot,
      transactionsRoot = Trie.EmptyRoot,
      receiptsRoot = Trie.EmptyRoot,
      logsBloom = Bloom.Empty,
      difficulty = UInt256.Zero,
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

  "a block this engine's header rules accept" should "be undecided, naming the difficulty and the seal" in
    assert(
      BlockValidator.validate(
        block = Block(successor, BlockBody(Seq.empty, Seq.empty, None)),
        rules = Classic,
        parent = Resolved(parent, Classic),
        engine = engine,
        world = new EvmFixtures.MapWorldState,
        destroyAccount = _ => (),
        stateRoot = () => StandInRoot,
        blockHashAt = _ => EvmFixtures.hash(0),
        chainId = NoChain
      ) == BlockVerdict.Undecided(RuleNotRun.EngineRules(Set(EngineRule.Difficulty, EngineRule.Seal))),
      "neither rule is applied to a header here, so no verdict over this engine may claim either was"
    )

  "a header carrying 33 bytes of extra data" should "be refused by the bound this engine inherits" in
    assert(
      engine.validateHeader(
        Resolved(successor.copy(extraData = Bytes.fromIArray(IArray.fill(33)(0x2a.toByte))), Classic),
        Resolved(parent, Classic)
      ) == Left(HeaderFault.ExtraDataAboveLimit(33, 32)),
      "a rule the engine runs refusing the header comes before any rule it names as unrun"
    )
