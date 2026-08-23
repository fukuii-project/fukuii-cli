package org.fukuii.consensus.pow

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, UInt256, UInt64}
import org.fukuii.chainspec.ConsensusRules
import org.fukuii.evm.{EvmFixtures, Word}
import org.fukuii.types.{BlockHeader, BlockNonce, Bloom, Seal}

/** What a proof-of-work block credits, and to whom.
  *
  * ==The amounts here are chosen to make integer division visible==
  *
  * A reward of thirty-two divides exactly by both divisors this emission uses,
  * so every figure below is exact and a wrong ordering of a multiplication and
  * a division would still show. The declining schedule is the subject of
  * [[ProofOfWorkEnginePropSpec]] instead, and the amounts there are the ones
  * ECIP-1017 states.
  *
  * ==Crediting and existing are different questions and both are asked==
  *
  * `EvmFixtures.MapWorldState` reports an account brought into being by a write
  * of zero as present, so a case about whether an account was reached asks
  * `accountExists` and never a balance.
  */
class ProofOfWorkEngineSpec extends AnyFlatSpec:

  private val beneficiary: Address = EvmFixtures.address(0x33)
  private val firstOmmerMiner: Address = EvmFixtures.address(0x44)
  private val secondOmmerMiner: Address = EvmFixtures.address(0x55)

  /** The block most cases settle, high enough that an ommer can precede it by
    * the whole range the age-scaled rule is stated for.
    */
  private val settlingBlock: BigInt = BigInt(100)

  private val withoutLadder: ProofOfWorkEngine = ProofOfWorkEngine()

  private def rules(reward: Long, creditsZero: Boolean = false): ConsensusRules =
    ConsensusRules(
      blockReward = UInt256.fromLong(reward).toOption.get,
      zeroRewardCreditsBeneficiary = creditsZero
    )

  /** An ommer at `number`, credited to `miner`. */
  private def ommerAt(number: BigInt, miner: Address): BlockHeader =
    BlockHeader(
      parentHash = EvmFixtures.hash(0),
      ommersHash = EvmFixtures.hash(0),
      beneficiary = miner,
      stateRoot = EvmFixtures.hash(0),
      transactionsRoot = EvmFixtures.hash(0),
      receiptsRoot = EvmFixtures.hash(0),
      logsBloom = Bloom.Empty,
      difficulty = UInt256.Zero,
      number = UInt64.fromBigInt(number).toOption.get,
      gasLimit = UInt64.Zero,
      gasUsed = UInt64.Zero,
      timestamp = UInt64.Zero,
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(mixHash = EvmFixtures.hash(0), nonce = BlockNonce.Zero)
    )

  /** An ommer `age` blocks behind [[settlingBlock]]. */
  private def ommer(age: Int, miner: Address): BlockHeader = ommerAt(settlingBlock - age, miner)

  private def settledAt(
      engine: ProofOfWorkEngine,
      consensus: ConsensusRules,
      number: BigInt,
      ommers: Seq[BlockHeader]
  ): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    engine.settlement(consensus, beneficiary, number, ommers)(world)
    world

  private def settled(
      engine: ProofOfWorkEngine,
      consensus: ConsensusRules,
      ommers: Seq[BlockHeader] = Seq.empty
  ): EvmFixtures.MapWorldState =
    settledAt(engine, consensus, settlingBlock, ommers)

  /** An engine whose eras are a hundred blocks long, so a boundary is reachable
    * without writing out a network's own five million.
    */
  private val shortEras: ProofOfWorkEngine = ProofOfWorkEngine(Some(BigInt(100)))

  /** A height inside [[shortEras]]'s fortieth era, which is where four fifths
    * applied that many times has driven a reward of five thousand to nothing.
    *
    * It is well below the era at which the exponent bound answers zero without
    * computing, so the zero here is the arithmetic's own and the case exercises
    * the path a bound would otherwise hide.
    */
  private val exhaustedHeight: BigInt = BigInt(3901)

  "a block with no ommers" should "credit its beneficiary exactly what the rules resolved" in
    assert(
      settled(withoutLadder, rules(32)).balanceOf(beneficiary) == Word(BigInt(32)),
      "the amount is the fork's answer, and an engine adding to it with no ommer to justify it is a different chain"
    )

  "an included ommer" should "add a thirty-second of the reward to what the winner is paid" in
    assert(
      settled(withoutLadder, rules(32), Seq(ommer(1, firstOmmerMiner))).balanceOf(beneficiary) == Word(BigInt(33)),
      "ethereum/execution-specs @ ccaaaba58 pays the winner BLOCK_REWARD + ommer_count * (BLOCK_REWARD // 32)"
    )

  it should "add that share once per ommer rather than once for the set" in
    assert(
      settled(withoutLadder, rules(32), Seq(ommer(1, firstOmmerMiner), ommer(2, secondOmmerMiner)))
        .balanceOf(beneficiary) == Word(BigInt(34)),
      "a share paid per set would under-pay every winner that included more than one, at every height"
    )

  it should "credit its own producer a share scaled by how far behind the block it is" in
    assert(
      settled(withoutLadder, rules(32), Seq(ommer(1, firstOmmerMiner))).balanceOf(firstOmmerMiner) == Word(BigInt(28)),
      "an ommer one block behind is paid seven eighths, which is the rule go-ethereum-pow and the specification agree on"
    )

  it should "pay each of two ommers by its own age rather than by the first one's" in
    assert(
      settled(withoutLadder, rules(32), Seq(ommer(1, firstOmmerMiner), ommer(4, secondOmmerMiner)))
        .balanceOf(secondOmmerMiner) == Word(BigInt(16)),
      "the age is read per ommer, and reading one age for the set pays the wrong account the wrong amount"
    )

  it should "leave a producer eight blocks behind uncreated, without refusing the block" in
    assert(
      !settled(withoutLadder, rules(32), Seq(ommer(8, firstOmmerMiner))).accountExists(firstOmmerMiner),
      "eight is inside the range the rule states and pays nothing there, so a network declining zero gains no leaf"
    )

  it should "be refused as a broken precondition where it is older than the rule is stated for" in
    assertThrows[IllegalStateException](
      settled(withoutLadder, rules(32), Seq(ommer(9, firstOmmerMiner)))
    )

  it should "be refused as a broken precondition where it does not precede the block" in
    assertThrows[IllegalStateException](
      settled(withoutLadder, rules(32), Seq(ommer(0, firstOmmerMiner)))
    )

  "an age the rule is not stated for" should "be refused whatever the mechanism pays" in
    assertThrows[IllegalStateException](
      settled(withoutLadder, ConsensusRules.Unrewarded, Seq(ommer(9, firstOmmerMiner)))
    )

  "a mechanism that credits nobody" should "leave an ommer's producer uncreated as well as the winner" in
    assert(
      !settled(withoutLadder, ConsensusRules.Unrewarded, Seq(ommer(1, firstOmmerMiner)))
        .accountExists(firstOmmerMiner),
      "besu returns before its ommer loop on the same check, and crediting one here would add a leaf that chain lacks"
    )

  "a reward of zero that is credited" should "bring an ommer's producer into being holding nothing" in
    assert(
      settled(withoutLadder, rules(0, creditsZero = true), Seq(ommer(1, firstOmmerMiner)))
        .accountExists(firstOmmerMiner),
      "the flag decides whether the accounts are reached at all, and it reaches the ommers by the same path"
    )

  "the ECIP-1017 ladder" should "pay the resolved amount unchanged through its first era" in
    assert(
      settled(shortEras, rules(32)).balanceOf(beneficiary) == settled(withoutLadder, rules(32)).balanceOf(beneficiary),
      "a network running the ladder from genesis must pay what one without it pays until the first boundary"
    )

  it should "still be paying the resolved amount on the last block of the first era" in
    assert(
      settledAt(shortEras, rules(5000), BigInt(100), Seq.empty).balanceOf(beneficiary) == Word(BigInt(5000)),
      "ECIP-1017 ends its first era ON the multiple, so stepping down a block early underpays exactly one block per era"
    )

  it should "step the reward down by a fifth on the block after an era ends" in
    assert(
      settledAt(shortEras, rules(5000), BigInt(101), Seq.empty).balanceOf(beneficiary) == Word(BigInt(4000)),
      "the proposal puts its second era at 5,000,001 rather than 5,000,000, which is where the step belongs"
    )

  it should "scale the winner's inclusion bonus by the era as well as the reward" in
    assert(
      settledAt(shortEras, rules(5000), BigInt(101), Seq(ommerAt(BigInt(100), firstOmmerMiner)))
        .balanceOf(beneficiary) == Word(BigInt(4125)),
      "a bonus taken over the unreduced amount would keep paying a first-era share out of a second-era reward"
    )

  it should "replace the age-scaled ommer rule with a flat thirty-second from its second era" in
    assert(
      settledAt(shortEras, rules(5000), BigInt(101), Seq(ommerAt(BigInt(100), firstOmmerMiner)))
        .balanceOf(firstOmmerMiner) == Word(BigInt(125)),
      "the proposal equalizes an ommer's reward with the winner's inclusion bonus from its second era, at a thirty-second"
    )

  it should "pay an ommer the same flat share whatever its age, once past the first era" in
    assert(
      settledAt(shortEras, rules(5000), BigInt(101), Seq(ommerAt(BigInt(95), firstOmmerMiner)))
        .balanceOf(firstOmmerMiner) == Word(BigInt(125)),
      "the age-scaled rule stops at the boundary, so an age that would have mattered before it must not after"
    )

  it should "leave the beneficiary uncreated where it has exhausted an amount that was not zero" in
    assert(
      !settledAt(shortEras, rules(5000), exhaustedHeight, Seq.empty).accountExists(beneficiary),
      "the amount the fork resolved is not the amount paid here, and it is the amount paid that decides the leaf"
    )

  it should "bring the beneficiary into being at an exhausted era where a reward of zero credits it" in
    assert(
      settledAt(shortEras, rules(5000, creditsZero = true), exhaustedHeight, Seq.empty).accountExists(beneficiary),
      "the flag is the whole of what separates the two state roots, and an exhausted amount does not overrule it"
    )
