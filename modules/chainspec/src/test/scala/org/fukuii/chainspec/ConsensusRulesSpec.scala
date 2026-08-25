package org.fukuii.chainspec

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt256

/** That the consensus facet compares by value, in each of its members.
  *
  * ==This is the property [[UpgradeRules]] requires of every facet, and it is
  * the one that fails silently==
  *
  * A facet whose members stopped taking part in the comparison would break
  * nothing a compiler reports: two identically-configured networks would simply
  * begin comparing unequal, or -- worse -- two differently-configured ones
  * equal, and the answer to *"do these two networks run the same rules"* would
  * then depend on how a caller happened to build its inputs. So each member is
  * pinned separately: a comparison that had dropped one of them still passes
  * every case that varies the other.
  */
class ConsensusRulesSpec extends AnyFlatSpec:

  private val rewarding: ConsensusRules =
    ConsensusRules(
      blockReward = UInt256.fromLong(7).toOption.get,
      zeroRewardCreditsBeneficiary = false,
      difficultyAdjustment = DifficultyAdjustment.Original,
      difficultyBombDelay = BigInt(0),
      difficultyBombPause = None,
      difficultyBombRemovedFrom = None,
      difficultyBoundDivisor = BigInt(2048)
    )

  /** [[rewarding]]'s members, written out again rather than copied, so that the
    * comparison below has two values to compare rather than one value twice.
    */
  private val rewardingAgain: ConsensusRules =
    ConsensusRules(
      blockReward = UInt256.fromLong(7).toOption.get,
      zeroRewardCreditsBeneficiary = false,
      difficultyAdjustment = DifficultyAdjustment.Original,
      difficultyBombDelay = BigInt(0),
      difficultyBombPause = None,
      difficultyBombRemovedFrom = None,
      difficultyBoundDivisor = BigInt(2048)
    )

  private val frontier: UpgradeRules = ChainspecFixtures.firstRules

  "consensus rules" should "compare equal to a separately built value with the same members" in
    assert(
      rewarding == rewardingAgain,
      "a facet that compared by identity would make two identical configurations look like different networks"
    )

  it should "be a separate value from the one it is compared with, or that case tests nothing" in
    assert(
      rewarding ne rewardingAgain,
      "comparing a value with itself passes whatever the comparison does, which is the calibration this case supplies"
    )

  it should "compare unequal when the reward differs" in
    assert(
      rewarding != rewardingAgain.copy(blockReward = UInt256.fromLong(8).toOption.get),
      "an emission difference is a state root difference on every block, so it cannot be outside the comparison"
    )

  it should "compare unequal when only the zero-reward rule differs" in
    assert(
      ConsensusRules.Unrewarded != ConsensusRules.Unrewarded.copy(zeroRewardCreditsBeneficiary = true),
      "the two states differ in whether the beneficiary account exists, which no other member records"
    )

  it should "compare unequal when only the difficulty algorithm differs" in
    assert(
      rewarding != rewardingAgain.copy(difficultyAdjustment = DifficultyAdjustment.Eip2),
      "the algorithm decides every block's target, so two networks differing in it do not run the same rules"
    )

  it should "compare unequal when only the bomb delay differs" in
    assert(
      rewarding != rewardingAgain.copy(difficultyBombDelay = BigInt(3000000)),
      "a delay is the one genuinely fork-varying member here, and it moves every difficulty past its own period"
    )

  it should "compare unequal when only the bomb pause differs" in
    assert(
      rewarding != rewardingAgain.copy(
        difficultyBombPause = Some(DifficultyBombPause(BigInt(3000000), BigInt(5000000)))
      ),
      "a paused term is flat where an unpaused one doubles, so the two answer different difficulties in the window"
    )

  it should "compare unequal when two pauses differ only in where they resume" in
    assert(
      rewardingAgain.copy(difficultyBombPause = Some(DifficultyBombPause(BigInt(3000000), BigInt(5000000)))) !=
        rewardingAgain.copy(difficultyBombPause = Some(DifficultyBombPause(BigInt(3000000), BigInt(6000000)))),
      "a nested record contributing only its first member to the comparison would call two windows one"
    )

  it should "compare unequal when only the bomb removal differs" in
    assert(
      rewarding != rewardingAgain.copy(difficultyBombRemovedFrom = Some(BigInt(5900000))),
      "a removed term and a delayed one differ at every height below the removal, where one is present and the other is not"
    )

  it should "compare unequal when only the bound divisor differs" in
    assert(
      rewarding != rewardingAgain.copy(difficultyBoundDivisor = BigInt(1024)),
      "the divisor sizes every adjustment step, so a comparison dropping it would call two chains one"
    )

  "the unrewarded rules" should "credit nothing" in
    assert(
      ConsensusRules.Unrewarded.blockReward == UInt256.Zero,
      "a rule set holding an emission nobody authored would state a network's schedule by accident"
    )

  it should "leave the beneficiary uncreated rather than crediting it nothing" in
    assert(
      !ConsensusRules.Unrewarded.zeroRewardCreditsBeneficiary,
      "of the two zero cases only this one adds no account to the state trie, which is the safe direction"
    )

  it should "state no rule that touches the exponential term" in
    assert(
      ConsensusRules.Unrewarded.difficultyBombPause.isEmpty &&
        ConsensusRules.Unrewarded.difficultyBombRemovedFrom.isEmpty,
      "this value is what a network answers before any proposal moved that term, and every network's rules " +
        "are built from it, so a default stating one of them would put a chain's own rule under every other chain"
    )

  "a rule set" should "compare unequal when only its consensus facet differs" in
    assert(
      frontier != frontier.copy(consensus = ConsensusRules.Unrewarded.copy(zeroRewardCreditsBeneficiary = true)),
      "a facet excluded from the enclosing comparison is one two networks can differ in undetectably"
    )
