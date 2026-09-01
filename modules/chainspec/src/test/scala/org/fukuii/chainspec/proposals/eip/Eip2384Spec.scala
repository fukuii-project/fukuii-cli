package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2384 changes, and the members beside it that must not move.
  *
  * ==Through [[Eip2384.component]], because the wiring is what is untested==
  *
  * The delta is one assignment and is reachable on its own, so a spec calling it
  * directly passes with the component wired to nothing.
  *
  * ==This is the assertion the published difficulty corpus cannot make==
  *
  * That corpus certifies the FIGURE and is already passing: it carries 4,254
  * cases under `dfEIP2384`, filed on the fork key `Berlin`, and
  * `org.fukuii.consensus.pow.certification.DifficultyCorpus` states what this
  * harness believes that key settles. What it does not say is which rule set on
  * which network reaches 9,000,000, because it reads no schedule -- deliberately,
  * so that it cannot certify a schedule against that schedule's own answer.
  * **So the corpus would go on passing over a rule set that never adopted this
  * document at all.** This file and the activation case in
  * `networks.ethereum.MainnetSpec` are the two halves it leaves open.
  *
  * ==The figure is restated here as a literal rather than read off the delta==
  *
  * So that a delta and its assertion cannot agree by sharing one wrong constant.
  * The controls below state the specific EARLIER figure rather than checking a
  * member for emptiness, so that a delta which moved the wrong member still
  * fails.
  */
class Eip2384Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.istanbul

  private val adopted: UpgradeRules = base.adopting(Eip2384.component)

  "adopting EIP-2384" should "hold the exponential term back by nine million blocks" in
    // NINE million, not the four million this document adds. The record carries
    // the cumulative delay because the mechanism reads it as a single
    // subtraction; a client accumulating per-fork increments carries 4,000,000
    // here and is not wrong, it is in a different frame.
    assert(
      adopted.consensus.difficultyBombDelay == BigInt(9000000),
      "the document states max(0, block.number - 9_000_000)"
    )

  it should "have held it back by five million before it was adopted" in
    assert(
      base.consensus.difficultyBombDelay == BigInt(5000000),
      "EIP-1234's figure is what this document extends"
    )

  it should "NOT hold it back by the four million it adds" in
    // The first specific wrong answer this document invites, named so a failure
    // says which mistake was made. Its own summary states the increment twice --
    // "another 4,000,000 blocks" and "pushes the ice age 4,000,000 blocks" --
    // and states the cumulative figure only in the Specification.
    assert(
      adopted.consensus.difficultyBombDelay != BigInt(4000000),
      "the increment was recorded where the cumulative figure belongs"
    )

  it should "NOT hold it back by the parent-relative figure one lower" in
    // The second, and it is the one that survives review, because a client
    // really does carry 8,999,999: go-ethereum-pow derives
    // bombDelayFromParent := bombDelay - 1 and subtracts THAT from the parent's
    // number, where the executable specification subtracts 9,000,000 from the
    // block's own. Two decompositions of one rule, and this record holds the
    // block-relative half.
    assert(
      adopted.consensus.difficultyBombDelay != BigInt(8999999),
      "a figure written for a mechanism that measures from the parent"
    )

  it should "settle that one member and nothing else on the consensus facet" in
    assert(
      adopted.consensus == base.consensus.copy(difficultyBombDelay = BigInt(9000000)),
      "the adopting rules differ from the earlier ones by something other than the delay"
    )

  it should "leave what a block pays its producer exactly where it found it" in
    // The half of EIP-1234 this document does NOT repeat. Both documents below
    // it moved a reward and a delay together in one specification; this one
    // moves the delay alone, so a component built by copying that shape would
    // cut the amount as well and pass every case above.
    assert(
      adopted.consensus.blockReward == base.consensus.blockReward,
      "a document stating one figure changed the amount a block pays"
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
    assert(adopted.evm eq base.evm, "a bomb-delay document must not reach the machine at all")

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(org.fukuii.chainspec.ProposalId.Eip(2384)),
      "the journal must record what was adopted"
    )
