package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.{HeaderConstants, ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3675 changes, and the members beside it that must not move.
  *
  * ==Through [[Eip3675.component]], because the wiring is what is untested==
  *
  * Each delta is reachable on its own, so a spec calling them directly passes
  * with the component wired to nothing. [[Eip3554Spec]] makes the same choice for
  * the same reason.
  *
  * ==No published corpus certifies these two members in this build==
  *
  * The state-test tiers this module certifies against are keyed on a fork and
  * assert a post state; what this document changes is a block reward and three
  * header fields, and neither is a thing a state test over one transaction can
  * observe. **So this file is the only assertion of the change that exists
  * here**, which is the position [[Eip3554Spec]] records for its own figure, and
  * the reason the negative controls below are written against specific wrong
  * values rather than against emptiness.
  */
class Eip3675Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.grayGlacier

  private val adopted: UpgradeRules = base.adopting(Eip3675.component)

  "adopting EIP-3675" should "credit the producer nothing" in
    assert(
      adopted.consensus.blockReward == UInt256.Zero,
      "the document removes increasing the beneficiary's balance by the block reward"
    )

  it should "not bring the beneficiary into being by crediting it nothing" in
    // The half of the pair a reading of the amount alone misses, and the half
    // that moves a state root. The document says REMOVE the credit rather than
    // set it to zero, and a reward of zero that still credits creates an empty
    // account and commits it to the state trie.
    assert(
      !adopted.consensus.zeroRewardCreditsBeneficiary,
      "a removed credit was implemented as a credit of zero, which is a different state root"
    )

  it should "have credited two ether before it was adopted" in
    // The control that makes the two assertions above mean something: without
    // it they would pass over a base that already paid nothing.
    assert(
      base.consensus.blockReward.toBigInt == BigInt(2) * BigInt(10).pow(18),
      "EIP-1234's figure is what this document removes"
    )

  it should "have credited a zero reward's beneficiary before it was adopted" in
    // The matching control for the second member, for the same reason.
    assert(
      base.consensus.zeroRewardCreditsBeneficiary,
      "the fork below this one already declined to credit, leaving the second member untested"
    )

  it should "hold three header fields at the constants the document states" in
    assert(
      adopted.header.constants == HeaderConstants.Eip3675,
      "the document's block-structure table is what this member gates"
    )

  it should "have held none of them before it was adopted" in
    assert(
      base.header.constants == HeaderConstants.Unconstrained,
      "the fork below this one already fixed the fields, leaving the change untested"
    )

  it should "keep the fee market it inherited" in
    // The header facet has two members and this document writes one of them.
    // A delta replacing the record rather than copying it would drop the market
    // silently, and every base-fee case in this build runs on a different fork.
    assert(
      adopted.header.feeMarket == base.header.feeMarket,
      "writing the constants member replaced the header facet and dropped the fee market"
    )

  it should "leave the exponential term's reference point exactly where it found it" in
    // The document removes the difficulty FORMULA rather than the figures that
    // parameterize it, so the six delays below this fork are carried through
    // rather than reset. A delta zeroing them would compile and would state a
    // figure no document does.
    assert(
      adopted.consensus.difficultyBombDelay == BigInt(11400000),
      "EIP-5133's figure was moved by a document that says nothing about it"
    )

  it should "leave the difficulty adjustment exactly where it found it" in
    assert(
      adopted.consensus.difficultyAdjustment == base.consensus.difficultyAdjustment,
      "the adjustment is not this document's to move"
    )

  it should "settle those two members and nothing else on the consensus facet" in
    assert(
      adopted.consensus ==
        base.consensus.copy(blockReward = UInt256.Zero, zeroRewardCreditsBeneficiary = false),
      "the adopting rules differ from the earlier ones by something other than the reward pair"
    )

  it should "leave the machine untouched" in
    // The other document in this upgrade is what reaches the machine. Asserted
    // with reference identity rather than equality, so a delta that rebuilt an
    // equal machine still fails.
    assert(adopted.evm eq base.evm, "a document with no machine delta reached the machine")

  it should "leave what admits a transaction untouched" in
    assert(adopted.admission eq base.admission, "a document with no admission delta reached admission")

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(ProposalId.Eip(3675)),
      "the journal must record what was adopted"
    )
