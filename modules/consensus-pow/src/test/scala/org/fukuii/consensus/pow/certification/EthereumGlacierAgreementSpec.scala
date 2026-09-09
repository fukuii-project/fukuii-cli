package org.fukuii.consensus.pow.certification

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, UInt256, UInt64}
import org.fukuii.chainspec.networks.ethereum.{Mainnet, Upgrades}
import org.fukuii.consensus.pow.EthashEngine
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** The two bomb delays this network's last mined upgrades state, asked of the
  * engine through the rule sets the schedule actually resolves.
  *
  * ==Why this is a THIRD position and not a duplicate of either neighbor==
  *
  * `EthashDifficultySpec` builds its rules inline, so it settles what the engine
  * does and says nothing about what any network states.
  * [[DifficultyCertificationSpec]] runs the published corpus against
  * [[DifficultyCorpus.rulesFor]], which is a table that file authors separately
  * and on purpose -- its own documentation gives the reason, that a harness
  * reading a schedule and then checking a corpus against what it read would be
  * true of any schedule whatsoever.
  *
  * **So both of them pass with `Upgrades.arrowGlacier` and
  * `Upgrades.grayGlacier` wrong**, and with the schedule pointing at neither.
  * This suite is the position that closes: the engine, asked by the rule sets
  * `Mainnet.schedule` resolves at the two heights, at heights where the delay
  * decides the answer alone.
  *
  * ==The agreement below is a COMPARISON, not the loop the corpus warns about==
  *
  * The distinction is the whole reason this file may read
  * [[DifficultyCorpus.rulesFor]] at all. The loop that file refuses is a harness
  * *sourcing* its belief from the schedule -- after which the corpus can only
  * confirm what the schedule already said. Nothing here does that: the harness
  * keeps its own figures, derived from the executable specification's
  * `BOMB_DELAY_BLOCKS` for each fork, and the schedule's come from the two
  * documents. This suite asserts the two independent derivations agree, which
  * is a claim that can fail, and does fail if either side is edited alone.
  *
  * ==Why an exponent and not a difficulty==
  *
  * A delay does not change the adjustment; it changes only where the
  * exponential term is measured from. Both cases below therefore hold the
  * parent difficulty and the timestamp gap fixed and read the term, so a
  * failure names the delay rather than some other rule that also moved.
  *
  * The three delays in play give three distinct exponents at each of the two
  * heights -- 38, 28 and 21 at the first, and 51, 41 and 34 at the second -- so
  * neither case can be satisfied by the rule set below it or the one above it.
  * **That separation is what makes the controls below real rather than
  * decorative.**
  */
class EthereumGlacierAgreementSpec extends AnyFlatSpec:

  private val engine: EthashEngine = EthashEngine()

  /** A parent difficulty that makes one step of adjustment exact.
    *
    * The bound divisor is 2,048, so a parent difficulty of 2,048 steps divides
    * evenly and the adjustment contributes exactly one step. Every expectation
    * below is then `parent + step + term`, with the term the only quantity a
    * delay can move.
    */
  private val step: BigInt = BigInt(6400)

  private val parentDifficulty: BigInt = step * 2048

  /** A gap short enough that the adjustment raises by exactly one step under
    * EIP-100, which is the adjustment every rule set here states.
    *
    * ==EIGHT, and NOT the nine `ClassicUpgradeDifficultySpec` uses==
    *
    * The two suites divide the gap by different constants because their rule
    * sets state different adjustments, and the figure does not carry across.
    * EIP-100 divides by 9, so a gap of 9 gives `1 - 9/9 = 0` and the adjustment
    * contributes NOTHING -- every expectation below would then be short by
    * exactly one step, in a way that reads as a wrong bomb term rather than as a
    * wrong gap. The other suite's rules divide by 10, where the same 9 gives
    * `1 - 0 = 1`.
    *
    * So this is the one constant in this file that must not be copied from the
    * suite beside it, which is why it is eight and says so.
    */
  private val shortGap: Long = 8L

  /** The parent of the block at `number`, which is where the engine reads the
    * difficulty it adjusts from.
    *
    * `number` is the block being settled, and the header this builds is one
    * below it -- the same convention `ClassicUpgradeDifficultySpec` uses, so
    * every height named in a case below is the height the specification names.
    */
  private def parent(number: BigInt): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.fromBigInt(parentDifficulty).toOption.get,
      number = UInt64.fromBigInt(number - 1).toOption.get,
      gasLimit = UInt64.Zero,
      gasUsed = UInt64.Zero,
      timestamp = UInt64.Zero,
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(mixHash = EvmFixtures.hash(0), nonce = BlockNonce.Zero)
    )

  /** What `rules` answer for the block at `number`, `shortGap` seconds after its
    * parent.
    *
    * The engine applies the delay to the block being settled rather than to its
    * parent, which is the executable specification's decomposition; a client
    * deriving a parent-relative offset one lower reaches the same rule.
    */
  private def answeredAt(rules: org.fukuii.chainspec.ConsensusRules, number: BigInt): BigInt =
    engine.difficulty(rules, parent(number), parentHasOmmers = false, UInt64.fromBits(shortGap)).toBigInt

  private def resolvedAt(height: Long): org.fukuii.chainspec.ConsensusRules =
    Mainnet.schedule.toOption.get.at(UInt64.fromBits(height), UInt64.Zero).consensus

  private val arrowHeight: Long = 13773000L
  private val grayHeight: Long = 15050000L

  // ── The schedule, through the engine ──────────────────────────────────────

  "the rules this network resolves at Arrow Glacier's height" should
    "measure the exponential term from ten million seven hundred thousand blocks below" in
    assert(
      answeredAt(resolvedAt(arrowHeight), BigInt(arrowHeight)) ==
        parentDifficulty + step + BigInt(2).pow(28),
      "the schedule resolves a rule set whose delay is not this upgrade's"
    )

  it should "NOT answer what the upgrade below it would" in
    // The control that makes the case above discriminate. London's delay at this
    // height gives 2^38 -- a different term by a factor of a thousand -- so an
    // entry pointing at the rules below would fail the case above rather than
    // pass it.
    assert(
      answeredAt(Upgrades.london.consensus, BigInt(arrowHeight)) ==
        parentDifficulty + step + BigInt(2).pow(38),
      "London's own delay does not produce the term this height's control expects"
    )

  "the rules this network resolves at Gray Glacier's height" should
    "measure the exponential term from eleven million four hundred thousand blocks below" in
    assert(
      answeredAt(resolvedAt(grayHeight), BigInt(grayHeight)) ==
        parentDifficulty + step + BigInt(2).pow(34),
      "the schedule resolves a rule set whose delay is not this upgrade's"
    )

  it should "NOT answer what the upgrade below it would" in
    assert(
      answeredAt(Upgrades.arrowGlacier.consensus, BigInt(grayHeight)) ==
        parentDifficulty + step + BigInt(2).pow(41),
      "Arrow Glacier's own delay does not produce the term this height's control expects"
    )

  it should "answer the upgrade below it ONE BLOCK EARLIER" in
    // Where the boundary actually is, read through the engine rather than
    // through the schedule alone. A schedule entry one block out satisfies every
    // case above and fails this one.
    assert(
      answeredAt(resolvedAt(grayHeight - 1), BigInt(grayHeight - 1)) ==
        parentDifficulty + step + BigInt(2).pow(41),
      "the block below Gray Glacier's height is settled by rules it does not run"
    )

  // ── The two independent derivations ───────────────────────────────────────

  "the schedule's delay and the certification harness's" should "agree for both glaciers" in
    // NOT a loop: the harness's figures come from the executable specification's
    // per-fork BOMB_DELAY_BLOCKS and the schedule's from the two documents.
    // Neither reads the other, so this can fail -- and it fails if either side
    // is edited alone, which is the reason it is written down.
    assert(
      DifficultyCorpus.rulesFor("ArrowGlacier").map(_.difficultyBombDelay) ==
        Some(Upgrades.arrowGlacier.consensus.difficultyBombDelay) &&
        DifficultyCorpus.rulesFor("GrayGlacier").map(_.difficultyBombDelay) ==
        Some(Upgrades.grayGlacier.consensus.difficultyBombDelay),
      "the published corpus is certified against a delay this network's schedule does not state"
    )

  it should "disagree where the upgrades genuinely differ" in
    // The negative control for the case above. Two figures compared for equality
    // pass trivially if both sides are read from one place; these two are not,
    // and the harness distinguishes the two forks exactly as the schedule does.
    assert(
      DifficultyCorpus.rulesFor("ArrowGlacier").map(_.difficultyBombDelay) !=
        DifficultyCorpus.rulesFor("GrayGlacier").map(_.difficultyBombDelay),
      "the harness gives one figure for two upgrades, so agreeing with it establishes nothing"
    )
