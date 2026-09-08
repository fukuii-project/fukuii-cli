package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** EIP-5133 -- the sixth bomb delay, and the whole of its own upgrade.
  *
  * ==THIS DOCUMENT STATES BOTH FRAMES, WHICH IS [[Eip2384]]'s SHAPE AND NOT
  * [[Eip3554]]'s==
  *
  * The distinction is worth drawing because the two neighbouring records
  * resolve it in opposite directions, and reading this document as either one
  * of them gets the figure wrong by 10,700,000.
  *
  * Its own description gives the increment -- *"Delays the difficulty bomb by a
  * further 700000 blocks"* -- while its abstract and Specification give the
  * cumulative total, *"`fake_block_number = max(0, block.number - 11_400_000) if
  * block.number >= FORK_BLOCK_NUMBER else block.number`"* (`ethereum/EIPs` @
  * `dbfa6bee8` (2026-08-26), `EIPS/eip-5133.md:23`, Final). **Both numbers are
  * the document's own, in the same file**, which is exactly the state
  * [[Eip2384]] needed a note to separate and the state [[Eip4345]] does not
  * have.
  *
  * **The figure below is the cumulative one**, because that is the frame
  * [[org.fukuii.chainspec.ConsensusRules.difficultyBombDelay]] holds.
  * `ethereum/execution-specs` @ `20f7f6271` (2026-08-26) settles which is which
  * without reading the prose at all:
  * `src/ethereum/forks/gray_glacier/fork.py:76` is `BOMB_DELAY_BLOCKS =
  * 11400000` against `forks/arrow_glacier/fork.py:76`'s `10700000`, and the
  * difference between those two is the 700,000 the description names.
  *
  * ==This document is its upgrade's ONLY member==
  *
  * `src/ethereum/forks/gray_glacier/__init__.py` lists what its fork changes and
  * lists one entry, this document. Its substantive difference from the module
  * below it is this figure and its activation; everything else differing between
  * the two is the fork naming itself and one reflowed argument list. **That is a
  * membership claim read from a membership statement, not from a diff size** --
  * see [[Eip4345]]'s note for why the diff is the wrong instrument here.
  *
  * ==And it is applied to the block, not to its parent==
  *
  * The caveat every record in this family carries. The executable specification
  * subtracts from the block itself -- `forks/gray_glacier/fork.py:956` is
  * `((int(block_number) - BOMB_DELAY_BLOCKS) // 100000) - 2` -- while a client
  * closing over the same figure derives a parent-relative offset one lower
  * before subtracting it. **Do not "correct" 11,400,000 to 11,399,999.**
  *
  * ==This is the last bomb delay this network states, and that is measured
  * rather than concluded from the mechanism==
  *
  * The reasoning alone would be suggestive and not evidence: the upgrade above
  * this one removes what the bomb was pressuring, since a network that no longer
  * mines has no difficulty for an exponential term to raise. **What settles it is
  * the sweep**, and it is a sweep of every numeric field rather than of the
  * fields named after a glacier -- three clients' Ethereum mainnet
  * configurations, read whole, carry nothing between this activation and the
  * transition, and nothing resembling a delay at any height above it. The
  * instrument is calibrated to catch a field not named after a fork, which is
  * the shape it would have to catch for this claim to be wrong.
  *
  * So a seventh record in this family would be a record about a different
  * network, and its absence here is a property of this network's history rather
  * than a gap.
  */
object Eip5133:

  /** The exponential term is measured from a point eleven million four hundred
    * thousand blocks below the block being settled.
    *
    * Cumulative, not the 700,000 this document adds to [[Eip4345]]'s figure --
    * see the type's own note above, because unlike [[Eip4345]] this document
    * states both and a reader can take either.
    */
  val difficultyBombDelay: ConsensusRules => ConsensusRules = _.copy(difficultyBombDelay = BigInt(11400000))

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Eip(5133), rules => rules.copy(consensus = difficultyBombDelay(rules.consensus)))
