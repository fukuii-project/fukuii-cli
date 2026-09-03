package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3554 changes, and the members beside it that must not move.
  *
  * ==Through [[Eip3554.component]], because the wiring is what is untested==
  *
  * The delta is one assignment and is reachable on its own, so a spec calling it
  * directly passes with the component wired to nothing. [[Eip2384Spec]] makes the
  * same choice for the same reason.
  *
  * ==The corpus that certifies [[Eip2384]]'s figure has no counterpart here==
  *
  * That one is carried by 4,254 cases under `dfEIP2384`. `ethereum/tests` @
  * `c67e485ff8` holds no `DifficultyTests` directory for this upgrade or this
  * document at all, and the listing calibrates itself: `dfArrowGlacier` and
  * `dfGrayGlacier` are present, and they are the same artifact for the same kind
  * of rule at the upgrades either side of this one.
  *
  * **So this file carries more weight than its sibling does, not less.** There,
  * these cases were the half a passing corpus left open -- which rule set reaches
  * the figure. Here they are the only assertion of the figure this module makes.
  *
  * ==The figure is restated here as a literal rather than read off the delta==
  *
  * So that a delta and its assertion cannot agree by sharing one wrong constant.
  * The controls below state the specific EARLIER figure rather than checking a
  * member for emptiness, so that a delta which moved the wrong member still
  * fails.
  */
class Eip3554Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.berlin

  private val adopted: UpgradeRules = base.adopting(Eip3554.component)

  "adopting EIP-3554" should "hold the exponential term back by nine million seven hundred thousand blocks" in
    assert(
      adopted.consensus.difficultyBombDelay == BigInt(9700000),
      "the document states max(0, block.number - 9_700_000)"
    )

  it should "have held it back by nine million before it was adopted" in
    // Berlin touches no consensus member, so what this extends is the figure
    // EIP-2384 set two upgrades below it.
    assert(
      base.consensus.difficultyBombDelay == BigInt(9000000),
      "EIP-2384's figure is what this document extends"
    )

  it should "NOT hold it back by the seven hundred thousand it adds" in
    // The increment appears NOWHERE in this document -- unlike EIP-2384, whose
    // own summary states its increment twice. It is a decomposition clients
    // keep, so the wrong answer here arrives from a configuration rather than
    // from the specification, and it is worth naming for that reason.
    assert(
      adopted.consensus.difficultyBombDelay != BigInt(700000),
      "an increment read off a client's configuration reached the cumulative field"
    )

  it should "NOT hold it back by the parent-relative figure one lower" in
    // go-ethereum-pow derives bombDelayFromParent := bombDelay - 1 and subtracts
    // THAT from the parent's number, where the executable specification
    // subtracts 9,700,000 from the block's own. Two decompositions of one rule,
    // and this record holds the block-relative half.
    assert(
      adopted.consensus.difficultyBombDelay != BigInt(9699999),
      "a figure written for a mechanism that measures from the parent"
    )

  it should "NOT carry the figure of either upgrade beside it" in
    // Both neighbors are bomb delays and nothing else, so a component copied
    // from one of them compiles, adopts, and states a figure off by one upgrade.
    // Arrow Glacier is 10,700,000 and Gray Glacier 11,400,000.
    assert(
      adopted.consensus.difficultyBombDelay != BigInt(10700000) &&
        adopted.consensus.difficultyBombDelay != BigInt(11400000),
      "a neighboring upgrade's delay was adopted under this document's number"
    )

  it should "settle that one member and nothing else on the consensus facet" in
    assert(
      adopted.consensus == base.consensus.copy(difficultyBombDelay = BigInt(9700000)),
      "the adopting rules differ from the earlier ones by something other than the delay"
    )

  it should "leave what a block pays its producer exactly where it found it" in
    // This upgrade changes the fee market, so a reader may expect it to touch
    // what a block pays. It does not: the reward is unchanged at London, and
    // this document in particular states one figure and no reward at all.
    assert(
      adopted.consensus.blockReward == base.consensus.blockReward,
      "a document stating one figure changed the amount a block pays"
    )

  it should "leave the difficulty adjustment exactly where it found it" in
    assert(
      adopted.consensus.difficultyAdjustment == base.consensus.difficultyAdjustment,
      "the adjustment is not this document's to move"
    )

  it should "leave the machine untouched" in
    assert(adopted.evm eq base.evm, "a bomb-delay document must not reach the machine at all")

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(org.fukuii.chainspec.ProposalId.Eip(3554)),
      "the journal must record what was adopted"
    )
