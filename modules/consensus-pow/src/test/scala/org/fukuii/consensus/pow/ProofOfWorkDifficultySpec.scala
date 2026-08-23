package org.fukuii.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment}
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** What the difficulty rule settles that the published corpus does not.
  *
  * ==This suite exists BECAUSE the corpus passes==
  *
  * `DifficultyCertificationSpec` runs 18,598 published cases and every one of
  * them agrees. That is evidence about the arithmetic and it is silent on four
  * things: a boundary the corpus never lands on, an order two implementation
  * families disagree about, and two preconditions the corpus cannot state
  * because it only contains cases a real chain produced. A rule certified
  * against a tier and nowhere else is certified against whatever that tier
  * happens to contain.
  *
  * The amounts are small and exact so that a wrong ordering of a multiplication
  * and a division would still show.
  */
class ProofOfWorkDifficultySpec extends AnyFlatSpec:

  private val engine: ProofOfWorkEngine = ProofOfWorkEngine()

  /** The floor no adjustment may take a block below, which several cases below
    * sit exactly on so that one step of adjustment crosses it.
    */
  private val minimumDifficulty: BigInt = BigInt(131072)

  private def parent(number: BigInt, difficulty: BigInt, timestamp: Long): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = EvmFixtures.address(0),
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.fromBigInt(difficulty).toOption.get,
      number = UInt64.fromBigInt(number).toOption.get,
      gasLimit = UInt64.Zero,
      gasUsed = UInt64.Zero,
      timestamp = UInt64.fromBigInt(BigInt(timestamp)).toOption.get,
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(mixHash = EvmFixtures.hash(0), nonce = BlockNonce.Zero)
    )

  private def rules(
      adjustment: DifficultyAdjustment,
      bombDelay: BigInt = BigInt(0),
      boundDivisor: BigInt = ConsensusRules.LaunchBoundDivisor
  ): ConsensusRules =
    ConsensusRules.Unrewarded.copy(
      difficultyAdjustment = adjustment,
      difficultyBombDelay = bombDelay,
      difficultyBoundDivisor = boundDivisor
    )

  /** A height low enough that the exponential term is still nothing, so a case
    * about the adjustment alone reads as the adjustment alone.
    */
  private val beforeTheTerm: BigInt = BigInt(1000)

  private def answered(
      adjustment: DifficultyAdjustment,
      parentDifficulty: BigInt,
      gap: Long,
      number: BigInt = beforeTheTerm,
      parentHasOmmers: Boolean = false,
      bombDelay: BigInt = BigInt(0)
  ): BigInt =
    val parentTimestamp = 1000L
    engine
      .difficulty(
        rules(adjustment, bombDelay),
        parent(number - 1, parentDifficulty, parentTimestamp),
        parentHasOmmers,
        UInt64.fromBigInt(BigInt(parentTimestamp + gap)).toOption.get
      )
      .toBigInt

  /** A difficulty large enough that one step of it is a whole number at every
    * multiplier used below, so no case is measuring a rounding artifact.
    */
  private val ampleDifficulty: BigInt = BigInt(2048) * BigInt(100000)

  private val step: BigInt = ampleDifficulty / 2048

  "the original algorithm" should "raise the difficulty by one step where the gap is below the limit" in
    assert(
      answered(DifficultyAdjustment.Original, ampleDifficulty, gap = 12) == ampleDifficulty + step,
      "EIP-2 quotes the rule it replaces as a multiplier of exactly 1 below thirteen seconds"
    )

  it should "lower it by one step where the gap is exactly the limit" in
    assert(
      answered(DifficultyAdjustment.Original, ampleDifficulty, gap = 13) == ampleDifficulty - step,
      "the comparison is strict in both sources, so thirteen lowers and only twelve raises"
    )

  it should "lower it by exactly one step however long the gap is" in
    assert(
      answered(DifficultyAdjustment.Original, ampleDifficulty, gap = 5000) == ampleDifficulty - step,
      "the rule is two-valued, and a gap-proportional answer here is EIP-2's algorithm applied before its fork"
    )

  "EIP-2's algorithm" should "lower the difficulty in proportion to the gap rather than by one step" in
    assert(
      answered(DifficultyAdjustment.Eip2, ampleDifficulty, gap = 40) == ampleDifficulty - step * 3,
      "a gap of forty gives a multiplier of 1 - 4, which is the continuity that makes this a different algorithm"
    )

  it should "still raise it by one step where the gap is under ten seconds" in
    assert(
      answered(DifficultyAdjustment.Eip2, ampleDifficulty, gap = 9) == ampleDifficulty + step,
      "the multiplier is 1 - 0 there, so the two algorithms agree on a fast block and part on a slow one"
    )

  it should "stop lowering it once the multiplier reaches ninety-nine steps" in
    assert(
      answered(DifficultyAdjustment.Eip2, ampleDifficulty, gap = 100000) == ampleDifficulty - step * 99,
      "EIP-2 floors the multiplier at -99 so that one very long gap cannot collapse the difficulty"
    )

  "EIP-100's algorithm" should "read the gap over a shorter interval than EIP-2 does" in
    assert(
      answered(DifficultyAdjustment.Eip100, ampleDifficulty, gap = 36) == ampleDifficulty - step * 3,
      "the divisor moves from ten to nine, which is the half of EIP-100 that is easy to miss beside the ommer term"
    )

  it should "raise the target by one further step where the parent carried ommers" in
    assert(
      answered(DifficultyAdjustment.Eip100, ampleDifficulty, gap = 36, parentHasOmmers = true) ==
        ampleDifficulty - step * 2,
      "the parent's ommers move the multiplier's base from one to two at the same gap"
    )

  it should "read no ommers where the parent carried none" in
    assert(
      answered(DifficultyAdjustment.Eip100, ampleDifficulty, gap = 36, parentHasOmmers = false) !=
        answered(DifficultyAdjustment.Eip100, ampleDifficulty, gap = 36, parentHasOmmers = true),
      "a rule ignoring the flag would answer the same difficulty for two blocks the chain gives different ones"
    )

  "the exponential term" should "be nothing below its second period" in
    assert(
      answered(DifficultyAdjustment.Original, ampleDifficulty, gap = 12, number = BigInt(199999)) ==
        ampleDifficulty + step,
      "the term starts at block 200,000, and starting it earlier changes every difficulty before that height"
    )

  it should "start at its second period" in
    assert(
      answered(DifficultyAdjustment.Original, ampleDifficulty, gap = 12, number = BigInt(200000)) ==
        ampleDifficulty + step + 1,
      "two raised to nothing is one, which is the term's first value and the block it lands on"
    )

  it should "be pushed back by the whole of the delay a fork resolved" in
    assert(
      answered(
        DifficultyAdjustment.Eip100,
        ampleDifficulty,
        gap = 8,
        number = BigInt(3200000),
        bombDelay = BigInt(3000000)
      ) == ampleDifficulty + step + 1,
      "a delay of three million puts block 3,200,000 where block 200,000 was, at the term's first value"
    )

  /** The order the floor and the exponential term are applied in, which the
    * whole published corpus cannot distinguish.
    *
    * ==Both orders pass every one of the 18,598 published cases==
    *
    * Measured before this case was written. They part only where an adjusted
    * difficulty falls below the floor while the term is not yet nothing, and no
    * block of either mainnet is anywhere near the floor -- which is what
    * `ethereum/execution-specs` @ `ccaaaba58` means by its own comment that the
    * difference *"does not matter"*.
    *
    * **So this case is the only thing in this repository that holds the order**,
    * and a rule that adopted the specification's order instead would answer
    * 131,072 here and pass certification unchanged.
    */
  "the minimum difficulty" should "floor the adjustment before the exponential term is added over it" in
    assert(
      answered(DifficultyAdjustment.Original, minimumDifficulty, gap = 13, number = BigInt(300000)) ==
        minimumDifficulty + 2,
      "go-ethereum-pow, besu-etc and OpenEthereum all floor the adjustment and add the term over it"
    )

  it should "still floor an adjustment that falls below it where there is no term" in
    assert(
      answered(DifficultyAdjustment.Original, minimumDifficulty, gap = 13) == minimumDifficulty,
      "the floor is what stops a chain adjusting itself below the difficulty its genesis block states"
    )

  "a block that does not follow its parent in time" should "be refused as a broken precondition" in
    assertThrows[IllegalStateException](
      answered(DifficultyAdjustment.Eip2, ampleDifficulty, gap = 0)
    )

  "a rule set stating no adjustment step" should "be refused rather than divided by" in
    assertThrows[IllegalStateException](
      engine.difficulty(
        rules(DifficultyAdjustment.Eip2, boundDivisor = BigInt(0)),
        parent(beforeTheTerm - 1, ampleDifficulty, 1000L),
        false,
        UInt64.fromBigInt(BigInt(1010)).toOption.get
      )
    )

  "a height whose exponential term no header could carry" should "be refused as a broken precondition" in
    assertThrows[IllegalStateException](
      engine.difficulty(
        rules(DifficultyAdjustment.Original),
        parent(BigInt(100000) * BigInt(300), ampleDifficulty, 1000L),
        false,
        UInt64.fromBigInt(BigInt(1010)).toOption.get
      )
    )
