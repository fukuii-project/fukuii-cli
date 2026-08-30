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
  * ECIP-1010's window as well as write the removal -- the one defect the rule
  * set adopting it is most exposed to -- those two suites ran 51 cases between
  * them, including all 384 of this network's published difficulty cases, and
  * every one agreed. This suite is the third position: the engine, asked by the
  * composed rule sets, at heights chosen so that each member of the composition
  * decides the answer alone.
  *
  * ==Every expectation is derivable without the corpus, and agrees with it==
  *
  * The inputs and answers below are the ones this network's own vectors state
  * under `ETC_DefuseDifficultyBomb` and `ETC_Atlantis`, across the three files
  * that certification suite reads. They are restated here rather than read, so
  * this suite runs whether or not the corpus is on the machine, and each is
  * written with the arithmetic that produces it so a reader can check it
  * against neither.
  *
  * **The three files do not carry one oracle between them, and reading them as
  * though they did overstates two of the three.**
  * `bomb_pause_and_removal.json` is the one naming three implementations
  * sharing no commit; `block_interval_adjustment.json` and
  * `uncle_adjustment.json` name one client and a re-derivation from EIP-2 and
  * EIP-100 that took no client as its source. That certification suite carries
  * the three apart and is the authority for which is which.
  *
  * **Two kinds of case below restate nothing and must not be read as agreeing
  * with anything.** Every seeded control states an answer for rules this
  * network never ran, which no vector could carry. And no vector is published
  * at 8,772,000: what `ETC_Atlantis` publishes either side of it at the same
  * gap -- 5,900,000 and 10,000,000 -- is the answer stated for that height
  * below, so the case there interpolates the corpus rather than restating it.
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

  /** The rules this network reached by adopting ten proposals over [[defuse]],
    * which is what the schedule resolves from block 8,772,000.
    */
  private val atlantis: ConsensusRules = Upgrades.atlantis.consensus

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
    *
    * @param parentHasOmmers
    *   whether that parent carried any, which only the ommer-aware rule reads.
    *   It defaults to the reading every rule below EIP-100 is indifferent to,
    *   so a case naming it is a case about EIP-100.
    */
  private def answered(
      rules: ConsensusRules,
      number: BigInt,
      gap: Long,
      parentHasOmmers: Boolean = false
  ): BigInt =
    engine.difficulty(rules, parent(number), parentHasOmmers, UInt64.fromBits(gap)).toBigInt

  /** A gap at which no adjustment rule this suite drives moves the target by
    * more than one step -- one step up under the two that predate EIP-100, and
    * nothing at all under EIP-100 itself, whose divisor it equals. So a case
    * about the exponential term is not also a case about the adjustment.
    */
  private val shortGap: Long = 9

  /** A gap shorter than either divisor, so the quotient is nothing and the
    * multiplier is the numerator alone -- which is the half of EIP-100 the
    * parent's ommers move.
    */
  private val numeratorGap: Long = 1

  /** The seedings, each removing or replacing exactly one member of a
    * composition.
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

  private def withoutTheOmmerAwareRule(rules: ConsensusRules): ConsensusRules =
    rules.copy(difficultyAdjustment = DifficultyAdjustment.Eip2)

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

  "the rules this network runs from block 8,772,000" should "answer under EIP-100's divisor" in
    // 13,107,200, unmoved. This height is above ECIP-1041's removal so there is
    // no term to add, and EIP-100's divisor is the gap itself, so the
    // multiplier is 1 - 9 / 9 = 0.
    assert(
      answered(atlantis, BigInt(8772000), gap = shortGap) == parentDifficulty,
      "the rule set that adopted EIP-100 answers under the divisor EIP-100 replaced"
    )

  it should "answer differently there under the rule EIP-100 replaced" in
    // The control the case above needs for its adjustment. EIP-2 divides by
    // ten, so the same gap gives 1 - 9 / 10 = 1 and the target rises a step.
    assert(
      answered(withoutTheOmmerAwareRule(atlantis), BigInt(8772000), gap = shortGap) ==
        parentDifficulty + step,
      "the two adjustment rules agree at this gap, so the case above does not discriminate them"
    )

  it should "answer differently there with the removal dropped" in
    // The second control that case needs, because its answer is an adjustment
    // of nothing AND a term of nothing, and only this separates them. Ten
    // proposals compose over ECIP-1041 here; with the removal gone and the
    // window still in force the exponent is
    // 8,772,000 / 100,000 - 20 - 2 = 65, the 20 being the window's own span in
    // periods.
    assert(
      answered(withoutTheRemoval(atlantis), BigInt(8772000), gap = shortGap) ==
        parentDifficulty + BigInt(2).pow(65),
      "the rule set that composes ten proposals over ECIP-1041 no longer carries the removal"
    )

  it should "raise by a further step where the parent carried ommers" in
    // 13,120,000. The gap is under both divisors, so the multiplier is the
    // numerator alone -- two rather than one, which is the whole of what
    // EIP-100 reads the parent's ommers for.
    assert(
      answered(atlantis, BigInt(150000), gap = numeratorGap, parentHasOmmers = true) ==
        parentDifficulty + step * 2,
      "the parent's ommers reach no rule this network composed here"
    )

  it should "answer differently there where the parent carried none" in
    // The control for the case above: 13,113,600, one step rather than two.
    // These vectors are published in both readings for this reason, and every
    // label below EIP-100 publishes the pair equal.
    assert(
      answered(atlantis, BigInt(150000), gap = numeratorGap, parentHasOmmers = false) ==
        parentDifficulty + step,
      "the two ommer readings agree here, so the case above measures nothing"
    )

  it should "still read ECIP-1010's window below the removal" in
    // 281,542,656 -- 13,107,200 and 2^28, with no adjustment between them. The
    // window freezes the term's reference point at 3,000,000, so the exponent
    // is 3,000,000 / 100,000 - 2 = 28 at every height inside it.
    assert(
      answered(atlantis, BigInt(4000000), gap = shortGap) == parentDifficulty + BigInt(2).pow(28),
      "adopting ten proposals over the removal disturbed the window underneath it"
    )

  it should "answer differently there with the window dropped" in
    // The control for the case above. Without the window the exponent is
    // 4,000,000 / 100,000 - 2 = 38.
    assert(
      answered(withoutTheWindow(atlantis), BigInt(4000000), gap = shortGap) ==
        parentDifficulty + BigInt(2).pow(38),
      "dropping ECIP-1010 from these rules changed no answer, so carrying it here asserts nothing"
    )
