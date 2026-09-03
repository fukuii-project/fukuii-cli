package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** EIP-3554 -- the fourth bomb delay, and nothing beside it.
  *
  * ==The same one-figure shape as [[Eip2384]], reached by a document that states
  * the figure once==
  *
  * Its Specification section is a single replacement, *"`fake_block_number =
  * max(0, block.number - 9_700_000) if block.number >= FORK_BLOCK_NUMBER else
  * block.number`"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-3554.md:26`,
  * Final), and it states no reward, no adjustment algorithm and no new rule.
  * `ethereum/execution-specs` @ `20f7f6271` reaches the same reading
  * independently at `forks/london/fork.py:78`, `BOMB_DELAY_BLOCKS = 9700000`,
  * against `forks/berlin/fork.py:70`'s `9000000`.
  *
  * **The module comparison [[Eip2384]] leans on does not transfer, and reading
  * it the same way would mislead.** There, two adjacent fork modules differed in
  * four lines and the diff itself argued that the fork changed one thing. Here
  * the same comparison is 141 insertions against 24 deletions, because this
  * upgrade also overhauls the fee market. **A diff that large says nothing about
  * whether this document is one figure** -- what settles that is the document,
  * which specifies one replacement.
  *
  * ==THE DELAY IS CUMULATIVE, AND THIS DOCUMENT ONLY EVER STATES THE CUMULATIVE
  * FIGURE==
  *
  * [[Eip2384]] needed a section separating two frames because its own text gave
  * both -- the 4,000,000 it added and the 9,000,000 that resulted. This one
  * gives only the total: the abstract says the bomb *"is adjusting 9,700,000
  * blocks later than the actual block number"* and the Specification subtracts
  * that same figure. **So the ambiguity runs the other way here.** The increment
  * is 700,000 and it appears in no part of the document; it is a decomposition
  * clients keep, which is why a configuration read for this figure can answer
  * 700,000 to a question the specification answers 9,700,000.
  *
  * `ethereum/go-ethereum-pow` @ `v1.10.26` states the cumulative form directly,
  * commenting `consensus/ethash/consensus.go:59` *"It offsets the bomb a total
  * of 9.7M blocks"* over `calcDifficultyEip3554 =
  * makeDifficultyCalculator(big.NewInt(9700000))`, which is the frame
  * [[org.fukuii.chainspec.ConsensusRules.difficultyBombDelay]] holds.
  *
  * ==And it is applied to the block, not to its parent==
  *
  * The caveat [[Eip2384]] and [[Eip1234]] both carry, unchanged and for the same
  * reason. The executable specification subtracts from the block itself --
  * `forks/london/fork.py:965` is `((int(block_number) - BOMB_DELAY_BLOCKS) //
  * 100000) - 2` -- while go-ethereum-pow closes over the same 9,700,000 and then
  * derives `bombDelayFromParent := bombDelay - 1` before subtracting it from the
  * parent's number. Two decompositions of one rule. **Do not "correct" 9,700,000
  * to 9,699,999** by analogy with a client whose mechanism differs from this
  * one's.
  *
  * ==No published difficulty tier carries this document's label==
  *
  * `ethereum/tests` @ `c67e485ff8` holds seven `DifficultyTests` directories and
  * none of them names this upgrade or this document. **The listing calibrates
  * itself**: `dfArrowGlacier` and `dfGrayGlacier` are present, and they are the
  * same artifact for the same kind of rule at the two upgrades either side of
  * this one, so a directory for this one would have been found. That is a
  * bounded claim about one corpus at one ref, and it is not a claim that nothing
  * anywhere exercises the figure -- a tier that validates a header's stated
  * difficulty reaches this rule without naming it, exactly as `dfEIP2384`'s cases
  * are filed under a label that document was not released with.
  */
object Eip3554:

  /** The exponential term is measured from a point nine million seven hundred
    * thousand blocks below the block being settled.
    *
    * Cumulative rather than incremental -- and here that is the document's own
    * framing rather than a choice between two it offers, which is what separates
    * this from [[Eip2384]]'s note.
    */
  val difficultyBombDelay: ConsensusRules => ConsensusRules = _.copy(difficultyBombDelay = BigInt(9700000))

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Eip(3554), rules => rules.copy(consensus = difficultyBombDelay(rules.consensus)))
