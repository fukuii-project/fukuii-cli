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
    ConsensusRules(blockReward = UInt256.fromLong(7).toOption.get, zeroRewardCreditsBeneficiary = false)

  /** [[rewarding]]'s members, written out again rather than copied, so that the
    * comparison below has two values to compare rather than one value twice.
    */
  private val rewardingAgain: ConsensusRules =
    ConsensusRules(blockReward = UInt256.fromLong(7).toOption.get, zeroRewardCreditsBeneficiary = false)

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

  "a rule set" should "compare unequal when only its consensus facet differs" in
    assert(
      frontier != frontier.copy(consensus = ConsensusRules.Unrewarded.copy(zeroRewardCreditsBeneficiary = true)),
      "a facet excluded from the enclosing comparison is one two networks can differ in undetectably"
    )
