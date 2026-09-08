package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** EIP-4345 -- the fifth bomb delay, and the whole of its own upgrade.
  *
  * ==One figure, and the document states only the cumulative one==
  *
  * Its Specification section is a single replacement, *"`fake_block_number =
  * max(0, block.number - 10_700_000) if block.number >= FORK_BLOCK_NUMBER else
  * block.number`"* (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26),
  * `EIPS/eip-4345.md:23`, Final), and its abstract subtracts the same figure.
  * The increment over [[Eip3554]] is 1,000,000 and appears nowhere in the
  * document, so this is [[Eip3554]]'s shape rather than [[Eip2384]]'s: there are
  * not two frames to separate, only one the document repeats.
  *
  * `ethereum/execution-specs` @ `20f7f6271` (2026-08-26) reaches the same
  * reading independently at `src/ethereum/forks/arrow_glacier/fork.py:76`,
  * `BOMB_DELAY_BLOCKS = 10700000`, against `forks/london/fork.py:78`'s
  * `9700000`.
  *
  * ==This document is its upgrade's ONLY member, which is a membership claim
  * and is sourced as one==
  *
  * `src/ethereum/forks/arrow_glacier/__init__.py` lists what its fork changes
  * and lists one entry, this document, where the module below it lists London's
  * five. **The line count of a diff between the two fork modules is not that
  * evidence and would mislead**: it is 117 lines, because a fork module names
  * itself throughout and because the transition-block clause London needed for
  * its own first block is dropped once that block is past. [[Eip3554]]'s own
  * note records the same trap from the other direction, where a large diff sat
  * under a one-figure document.
  *
  * ==And it is applied to the block, not to its parent==
  *
  * The caveat [[Eip3554]], [[Eip2384]] and [[Eip1234]] all carry, unchanged and
  * for the same reason. The executable specification subtracts from the block
  * itself -- `forks/arrow_glacier/fork.py:956` is `((int(block_number) -
  * BOMB_DELAY_BLOCKS) // 100000) - 2` -- while a client closing over the same
  * figure derives a parent-relative offset one lower before subtracting it.
  * Two decompositions of one rule. **Do not "correct" 10,700,000 to 10,699,999**
  * by analogy with a client whose mechanism differs from this one's.
  *
  * ==A published difficulty tier DOES carry this document's upgrade, and this
  * build already runs it==
  *
  * Unlike [[Eip3554]], whose note records that no directory names it,
  * `ethereum/tests` @ `c67e485ff` (2025-06-04) holds `DifficultyTests` for both
  * this upgrade and the one above it. **The certification that reads them is
  * older than this file**: the difficulty harness has stated 10,700,000 for this
  * upgrade's name since before any schedule reached it, deliberately deriving
  * that figure from the specification rather than from a schedule, so that
  * checking a schedule against it is a real comparison rather than a loop.
  *
  * So the figure below and the figure that harness holds are two independent
  * derivations of one value, and they are required to agree.
  */
object Eip4345:

  /** The exponential term is measured from a point ten million seven hundred
    * thousand blocks below the block being settled.
    *
    * Cumulative, and here that is the document's own and only framing rather
    * than a choice between two it offers.
    */
  val difficultyBombDelay: ConsensusRules => ConsensusRules = _.copy(difficultyBombDelay = BigInt(10700000))

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Eip(4345), rules => rules.copy(consensus = difficultyBombDelay(rules.consensus)))
