package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1234 changes, both halves of it, and the members beside
  * them that must not move.
  *
  * ==Through [[Eip1234.component]], because the wiring is what is untested==
  *
  * Each delta is one assignment and is reachable on its own, so a spec calling
  * either directly passes with the component wired to nothing.
  *
  * ==Both figures are restated here as literals rather than read off the delta==
  *
  * So that a delta and its assertion cannot agree by sharing one wrong
  * constant. The controls below state the specific EARLIER figure rather than
  * checking a member for emptiness, so that a delta which moved the wrong
  * member still fails.
  */
class Eip1234Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.byzantium

  private val adopted: UpgradeRules = base.adopting(Eip1234.component)

  private val twoEther: UInt256 =
    UInt256.fromBigInt(BigInt(2) * BigInt(10).pow(18)).getOrElse(fail("two ether does not fit"))

  private val threeEther: UInt256 =
    UInt256.fromBigInt(BigInt(3) * BigInt(10).pow(18)).getOrElse(fail("three ether does not fit"))

  "adopting EIP-1234" should "pay two ether for a block" in
    assert(adopted.consensus.blockReward == twoEther, "the document states 2_000_000_000_000_000_000 wei")

  it should "have paid three ether before it was adopted" in
    // The control, stated as the specific earlier figure. A case asserting only
    // that the amount moved would pass for any wrong new value.
    assert(base.consensus.blockReward == threeEther, "EIP-649's figure is what this document reduces")

  it should "hold the exponential term back by five million blocks" in
    // FIVE million, not the two million this document adds. The record carries
    // the cumulative delay because the mechanism reads it as a single
    // subtraction; a client accumulating per-fork increments carries 2,000,000
    // here and is not wrong, it is in a different frame.
    assert(
      adopted.consensus.difficultyBombDelay == BigInt(5000000),
      "the document states max(0, block.number - 5_000_000)"
    )

  it should "have held it back by three million before it was adopted" in
    assert(base.consensus.difficultyBombDelay == BigInt(3000000), "EIP-649's figure is what this document extends")

  it should "NOT hold it back by the two million it adds" in
    // The specific wrong answer this document invites, named so a failure says
    // which mistake was made. 2,000,000 is what the chain-spec layers of two
    // production clients carry at this height, on top of an earlier entry they
    // then sum -- correct there, wrong in this record.
    assert(
      adopted.consensus.difficultyBombDelay != BigInt(2000000),
      "the increment was recorded where the cumulative figure belongs"
    )

  it should "settle those two and nothing else on the consensus facet" in
    assert(
      adopted.consensus == base.consensus.copy(blockReward = twoEther, difficultyBombDelay = BigInt(5000000)),
      "the adopting rules differ from the earlier ones by something other than the two figures"
    )

  it should "leave the difficulty adjustment exactly where it found it" in
    // The document delays the exponential term; it does not change how
    // difficulty is targeted. Those are different members and the second is
    // EIP-100's.
    assert(
      adopted.consensus.difficultyAdjustment == base.consensus.difficultyAdjustment,
      "the adjustment is not this document's to move"
    )

  it should "leave the machine untouched" in
    assert(adopted.evm eq base.evm, "a reward-and-bomb document must not reach the machine at all")

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(org.fukuii.chainspec.ProposalId.Eip(1234)),
      "the journal must record what was adopted"
    )
