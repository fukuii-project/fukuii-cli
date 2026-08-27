package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-649 changes, both halves of it, and the member beside them
  * that must not move.
  *
  * ==Through [[Eip649.component]], because the wiring is what is untested==
  *
  * Each delta is one assignment and is reachable on its own, so a spec calling
  * either directly passes with the component wired to nothing. What a network
  * adopts is the component.
  *
  * ==Both figures are restated here from the document rather than read off it==
  *
  * The cases below write three ether and three million out as literals, so that
  * a delta and its assertion cannot agree by sharing one wrong constant. The
  * document is `ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-649.md`, Final.
  */
class Eip649Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip649.component)

  private def ether(count: Int): UInt256 =
    UInt256
      .fromBigInt(BigInt(count) * BigInt(10).pow(18))
      .getOrElse(fail("a whole number of ether does not fit a 256-bit quantity"))

  "adopting EIP-649" should "pay three ether for a block" in
    assert(
      adopted.consensus.blockReward == ether(3),
      "the amount the document names is not the one adopting it produced"
    )

  it should "have paid the launch amount before it was adopted" in
    // The control, stated as the specific earlier figure: a case asserting only
    // that the two differ would pass against any reduction whatsoever.
    assert(
      base.consensus.blockReward == ether(5),
      "the preceding rules already paid something other than the launch amount"
    )

  it should "hold the exponential term back by three million blocks" in
    assert(
      adopted.consensus.difficultyBombDelay == BigInt(3000000),
      "the delay the document names is not the one adopting it produced"
    )

  it should "have held it back by nothing before it was adopted" in
    // The control. Zero is a real value here rather than an absence -- every
    // fork predating the first delay proposal answers it -- so the earlier
    // figure is asserted rather than the member being checked for emptiness.
    assert(
      base.consensus.difficultyBombDelay == BigInt(0),
      "the preceding rules already held the exponential term back, which no earlier proposal does"
    )

  it should "settle those two and nothing else on the consensus facet" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as either named one failing to move.
    assert(
      adopted.consensus == base.consensus.copy(blockReward = ether(3), difficultyBombDelay = BigInt(3000000)),
      "the reduced rules differ from the earlier ones by something other than the amount and the delay"
    )

  it should "leave the difficulty adjustment exactly where it found it" in
    // The member a reader is most likely to conflate this document with,
    // because the two are adopted together on this network and both concern
    // difficulty. EIP-100 replaces the adjustment formula; this document moves
    // the height the exponential term beside it is measured from.
    assert(
      adopted.consensus.difficultyAdjustment == base.consensus.difficultyAdjustment,
      "a delay to the exponential term changed the adjustment algorithm beside it"
    )

  it should "reach no facet outside consensus" in
    assert(
      (adopted.evm eq base.evm) && (adopted.execution eq base.execution) &&
        (adopted.admission eq base.admission),
      "a rule about emission and the exponential term altered the machine, settlement or admission"
    )
