package org.fukuii.consensus.pow

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.{Bytes, UInt256, UInt64}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.consensus.{EngineRule, HeaderFault, Resolved}
import org.fukuii.evm.EvmFixtures
import org.fukuii.trie.Trie
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** What this engine's header rules answer, as a table of named cases.
  *
  * ==Every expected figure is worked by hand, and none is the engine's own==
  *
  * The parent states a difficulty of `8,388,608`, so one adjustment step is
  * `8,388,608 / 2048 = 4,096`, and the successor sits at block 101, where no
  * rule set's bomb has started. EIP-100's multiplier is `raised - gap / 9`
  * floored at `-99`, with `raised` two where the parent included ommers and one
  * where it did not; the original rule adds a step under a thirteen-second gap
  * and subtracts one at or above it. So nine seconds under EIP-100 requires the
  * parent's own figure, or a step more with parent ommers, and nine seconds
  * under the original rule requires a step more.
  *
  * ==Why each row is here==
  *
  * The seal rows show the configuration decides the seal and nothing else; the
  * ommers rows show whether the parent included ommers is read off its
  * commitment rather than assumed; the EIP-3675 rows show the rules that fix
  * the difficulty never reach the formula, which would refuse their zero; the
  * first-block row shows the formula is the block's own fork's, where EIP-2's
  * over the same nine seconds would require a step more; and the last row shows
  * a rule the inherited answer runs outranks the difficulty.
  *
  * `EthashEngineHeaderSpec` holds what a whole block answers once these rules
  * have run over it.
  */
class EthashEngineHeaderPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def difficultyOf(value: Long): UInt256 = UInt256.fromLong(value).fold(error => fail(error.toString), identity)

  private val ethash: EthashEngine = EthashEngine()

  private val noProof: EthashEngine = EthashEngine(sealEngine = SealEngine.NoProof)

  private val parent: BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(1),
      ommersHash = BlockHeader.EmptyOmmersHash,
      beneficiary = EvmFixtures.address(0x33),
      stateRoot = EvmFixtures.hash(2),
      transactionsRoot = Trie.EmptyRoot,
      receiptsRoot = Trie.EmptyRoot,
      logsBloom = Bloom.Empty,
      difficulty = difficultyOf(8388608L),
      number = UInt64.fromBits(100L),
      gasLimit = UInt64.fromBits(8000000L),
      gasUsed = UInt64.Zero,
      timestamp = UInt64.fromBits(1000L),
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(EvmFixtures.hash(0), BlockNonce.Zero),
      tail = None
    )

  /** The parent, committing to a list of ommers. */
  private val parentWithOmmers: BlockHeader = parent.copy(ommersHash = EvmFixtures.hash(9))

  /** The successor at `gap` seconds, stating `difficulty`. */
  private def successor(difficulty: Long, gap: Long = 9L): BlockHeader =
    parent.copy(
      parentHash = parent.hash,
      number = UInt64.fromBits(101L),
      timestamp = UInt64.fromBits(1000L + gap),
      difficulty = difficultyOf(difficulty)
    )

  private val Rows = Table(
    ("case", "engine", "rules", "parent rules", "parent", "header", "answer"),
    (
      "the difficulty required, sealed by ethash",
      ethash,
      ethereum.Upgrades.byzantium,
      ethereum.Upgrades.byzantium,
      parent,
      successor(8388608L),
      Right(Set(EngineRule.Seal))
    ),
    (
      "the difficulty required, declaring no proof",
      noProof,
      ethereum.Upgrades.byzantium,
      ethereum.Upgrades.byzantium,
      parent,
      successor(8388608L),
      Right(Set.empty[EngineRule])
    ),
    (
      "one more than required, sealed by ethash",
      ethash,
      ethereum.Upgrades.byzantium,
      ethereum.Upgrades.byzantium,
      parent,
      successor(8388609L),
      Left(HeaderFault.DifficultyMismatch(difficultyOf(8388609L), difficultyOf(8388608L)))
    ),
    (
      "the figure a parent with no ommers requires, under a parent that included some",
      noProof,
      ethereum.Upgrades.byzantium,
      ethereum.Upgrades.byzantium,
      parentWithOmmers,
      successor(8388608L).copy(parentHash = parentWithOmmers.hash),
      Left(HeaderFault.DifficultyMismatch(difficultyOf(8388608L), difficultyOf(8392704L)))
    ),
    (
      "the figure a parent with ommers requires, under that parent",
      noProof,
      ethereum.Upgrades.byzantium,
      ethereum.Upgrades.byzantium,
      parentWithOmmers,
      successor(8392704L).copy(parentHash = parentWithOmmers.hash),
      Right(Set.empty[EngineRule])
    ),
    (
      "the original rule's step up, under thirteen seconds",
      noProof,
      ethereum.Upgrades.frontier,
      ethereum.Upgrades.frontier,
      parent,
      successor(8392704L),
      Right(Set.empty[EngineRule])
    ),
    (
      "the original rule's step down, at thirteen seconds",
      noProof,
      ethereum.Upgrades.frontier,
      ethereum.Upgrades.frontier,
      parent,
      successor(8384512L, gap = 13L),
      Right(Set.empty[EngineRule])
    ),
    (
      "a zero difficulty under EIP-3675's constants, sealed by ethash",
      ethash,
      ethereum.Upgrades.paris,
      ethereum.Upgrades.paris,
      parent,
      successor(0L),
      Right(Set.empty[EngineRule])
    ),
    (
      "a zero difficulty under EIP-3675's constants, declaring no proof",
      noProof,
      ethereum.Upgrades.paris,
      ethereum.Upgrades.paris,
      parent,
      successor(0L),
      Right(Set.empty[EngineRule])
    ),
    (
      "the first block under EIP-100's rule, over a parent under EIP-2's",
      noProof,
      ethereum.Upgrades.byzantium,
      ethereum.Upgrades.spuriousDragon,
      parent,
      successor(8388608L),
      Right(Set.empty[EngineRule])
    ),
    (
      "extra data above the bound and a wrong difficulty",
      ethash,
      ethereum.Upgrades.byzantium,
      ethereum.Upgrades.byzantium,
      parent,
      successor(8388609L).copy(extraData = Bytes.fromIArray(IArray.fill(33)(0x2a.toByte))),
      Left(HeaderFault.ExtraDataAboveLimit(33, 32))
    )
  )

  property("the header rules answer each case as worked by hand") {
    forAll(Rows) {
      (
          label: String,
          engine: EthashEngine,
          rules: UpgradeRules,
          parentRules: UpgradeRules,
          parentHeader: BlockHeader,
          header: BlockHeader,
          answer: Either[HeaderFault, Set[EngineRule]]
      ) =>
        val answered = engine.validateHeader(Resolved(header, rules), Resolved(parentHeader, parentRules))
        assert(answered == answer, label + ": answered " + answered.toString)
    }
  }
