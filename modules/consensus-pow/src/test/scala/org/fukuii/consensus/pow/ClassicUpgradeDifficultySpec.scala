package org.fukuii.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment}
import org.fukuii.chainspec.networks.ethereumclassic.Upgrades
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** The difficulty rule driven by the rule sets this network actually composed,
  * rather than by rules assembled for the occasion.
  *
  * ==Why this is neither of the two suites beside it==
  *
  * [[EthashDifficultySpec]] builds its rules inline, so it settles what the
  * engine does and says nothing about what any network states.
  * `org.fukuii.consensus.pow.certification.ClassicDifficultyCertificationSpec`
  * runs this network's own vectors against a rule table its harness authors
  * separately, deliberately -- a harness reading a schedule and then checking a
  * corpus against what it read would be true of any schedule whatsoever.
  *
  * **So both of them pass with
  * `org.fukuii.chainspec.networks.ethereumclassic.Upgrades` wrong, and that is
  * measured rather than argued.** With ECIP-1041's delta seeded to clear
  * ECIP-1010's window as well as write the removal -- the one defect this
  * upgrade is most exposed to -- those two suites ran 51 cases between them,
  * including all 384 of this network's published difficulty cases, and every
  * one agreed. This suite is the third position: the engine, asked by the
  * composed rule set, at heights chosen so that each member of the composition
  * decides the answer alone.
  *
  * ==Every expectation is derivable without the corpus, and agrees with it==
  *
  * The inputs and answers below are the ones this network's own vectors state
  * under `ETC_DefuseDifficultyBomb` -- `bomb_pause_and_removal.json` and
  * `block_interval_adjustment.json`, whose oracles are three implementations
  * sharing no commit, named in that certification suite. They are restated
  * here rather than read, so this suite runs whether or not the corpus is on
  * the machine, and each is written with the arithmetic that produces it so a
  * reader can check it against neither.
  *
  * A parent difficulty of 13,107,200 makes one step of adjustment
  * 13,107,200 / 2,048 = 6,400, which is a whole number, so no case below is
  * measuring a rounding artifact.
  */
class ClassicUpgradeDifficultySpec extends AnyFlatSpec:

  private val engine: EthashEngine = EthashEngine()

  /** The rules this network reached by adopting ECIP-1041 over the emission
    * step, which is what the schedule resolves from block 5,900,000.
    */
  private val defuse: ConsensusRules = Upgrades.defuse.consensus

  /** One step of adjustment at the parent difficulty every case uses. */
  private val step: BigInt = BigInt(6400)

  private val parentDifficulty: BigInt = step * 2048

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

  /** What `rules` answer for the block at `number`, `gap` seconds after its
    * parent.
    */
  private def answered(rules: ConsensusRules, number: BigInt, gap: Long): BigInt =
    engine.difficulty(rules, parent(number), parentHasOmmers = false, UInt64.fromBits(gap)).toBigInt

  /** A gap short enough that both adjustment rules raise by one step, so a case
    * about the exponential term is not also a case about the adjustment.
    */
  private val shortGap: Long = 9

  /** The three seedings, each removing exactly one member of the composition.
    *
    * They are applied to the composed rules rather than to rules built here,
    * so a seeding measures the departure from what this network states rather
    * than the distance between two independently authored values.
    */
  private def withoutTheWindow(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombPause = None)

  private def withoutTheRemoval(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyBombRemovedFrom = None)

  private def withoutTheGraduatedRule(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyAdjustment = DifficultyAdjustment.Original)

  "the rules this network runs from block 5,900,000" should "still read ECIP-1010's window below that height" in
    // 13,113,600 + 2^28. The window freezes the term's reference point at
    // 3,000,000, so the exponent is 3,000,000 / 100,000 - 2 = 28 at every
    // height inside it, and 4,000,000 is inside it.
    assert(
      answered(defuse, BigInt(4000000), gap = shortGap) == parentDifficulty + step + BigInt(2).pow(28),
      "the height resolving these rules is above the window, but the heights they answer for are not"
    )

  it should "answer differently there with the window dropped" in
    // The control the case above needs, and the one this whole upgrade turns
    // on: ECIP-1041 defers to ECIP-1010 below its own height rather than
    // replacing it, so dropping the window has to move an answer BELOW the
    // removal. Without the window the exponent is 4,000,000 / 100,000 - 2 = 38.
    assert(
      answered(withoutTheWindow(defuse), BigInt(4000000), gap = shortGap) ==
        parentDifficulty + step + BigInt(2).pow(38),
      "dropping ECIP-1010 from these rules changed no answer, so carrying it here asserts nothing"
    )

  it should "compute no term at the height ECIP-1041 names" in
    // 13,113,600 and no term at all. The removal is asked before any rule that
    // would move the term instead, so the window under it is never reached.
    assert(
      answered(defuse, BigInt(5900000), gap = shortGap) == parentDifficulty + step,
      "the first block this network computes no exponential term for still has one"
    )

  it should "answer differently there with the removal dropped" in
    // The control for the case above. With the window still in force and no
    // removal the exponent is 5,900,000 / 100,000 - 20 - 2 = 37, the 20 being
    // the window's own span in periods.
    assert(
      answered(withoutTheRemoval(defuse), BigInt(5900000), gap = shortGap) ==
        parentDifficulty + step + BigInt(2).pow(37),
      "dropping ECIP-1041 from these rules changed no answer at the height it names"
    )

  it should "still apply EIP-2's graduated adjustment" in
    // 13,107,200 - 2 * 6,400. A gap of 30 seconds gives 1 - 30 / 10 = -2 under
    // the graduated rule, and block 150,000 is low enough that the exponential
    // term is nothing under either reading, so the case is the adjustment
    // alone. ECIP-1041 restates this rule in the closing line of its own
    // implementation block, which is the reading that would lose it.
    assert(
      answered(defuse, BigInt(150000), gap = 30) == parentDifficulty - step * 2,
      "the rule set that removes the exponential term also lost the adjustment it inherited"
    )

  it should "answer differently there under the rule EIP-2 replaced" in
    // The control for the case above. The original rule is a single step in
    // whichever direction the gap falls, so it loses one step of the two.
    assert(
      answered(withoutTheGraduatedRule(defuse), BigInt(150000), gap = 30) == parentDifficulty - step,
      "the two adjustment rules agree at this gap, so the case above does not discriminate them"
    )
