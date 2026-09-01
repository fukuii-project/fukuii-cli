package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** EIP-2384 -- the third bomb delay, and nothing beside it.
  *
  * ==One figure, where [[Eip649]] and [[Eip1234]] each carried two==
  *
  * Both documents below this one moved the exponential term and cut what
  * producing a block pays, in one specification. This one moves the term alone:
  * its Specification section is a single replacement, *"`fake_block_number =
  * max(0, block.number - 9_000_000) if block.number >= MUIR_GLACIER_FORK_BLKNUM
  * else block.number`"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2384.md:25`,
  * Final), and it states no reward, no adjustment algorithm and no new rule.
  * `ethereum/execution-specs` @ `20f7f6271` reaches the same reading
  * independently: `forks/muir_glacier/fork.py` declares `BOMB_DELAY_BLOCKS =
  * 9000000` at `:65` against `forks/istanbul/fork.py:65`'s `5000000`. Those two
  * modules differ in that line and one docstring, four changed lines in all,
  * against 41 changed lines between the same file and `forks/berlin/fork.py` --
  * so the comparison can report a fork that changed more than one thing, and
  * what it reports here is one number.
  *
  * ==THE DELAY IS CUMULATIVE AND 9,000,000 IS THE WHOLE OF IT==
  *
  * The increment this document adds is 4,000,000 and it says so twice -- *"delay
  * the difficulty bomb for another 4,000,000 blocks"* and *"this pushes the ice
  * age 4,000,000 blocks from ~block 8,800,000 NOT from when this EIP is
  * activated in a fork"*. **The figure this record holds is the cumulative
  * 9,000,000**, because
  * [[org.fukuii.chainspec.ConsensusRules.difficultyBombDelay]] is read as a
  * single subtraction rather than accumulated across entries, and that is the
  * same frame [[Eip1234]]'s own note sets out.
  *
  * The document's abstract states the cumulative figure three ways over, which
  * is what makes the two frames separable rather than a matter of reading:
  * *"9 million blocks later than the Homestead fork, which is also 7 million
  * blocks later than the Byzantium fork and 4 million blocks later than the
  * Constantinople fork"*. `ethereum/go-ethereum-pow` @ `v1.10.26` writes both
  * halves on adjacent lines -- `consensus/ethash/consensus.go:64` comments *"It
  * offsets the bomb 4M blocks from Constantinople, so in total 9M blocks"* over
  * `calcDifficultyEip2384 = makeDifficultyCalculator(big.NewInt(9000000))`.
  *
  * ==And it is applied to the block, not to its parent==
  *
  * The caveat [[Eip1234]] carries, at the figure that makes it easiest to trip
  * on. The executable specification subtracts from the block itself --
  * `forks/muir_glacier/fork.py:827` is `((int(block_number) -
  * BOMB_DELAY_BLOCKS) // 100000) - 2` -- while go-ethereum-pow closes over the
  * same 9,000,000 and then derives `bombDelayFromParent := bombDelay - 1` before
  * subtracting it from the parent's number. Two decompositions of one rule.
  * **Do not "correct" 9,000,000 to 8,999,999** by analogy with a client whose
  * mechanism differs from this one's.
  *
  * ==The name a corpus files this document under is not the one it was released
  * with==
  *
  * The published difficulty vectors key these cases on `Berlin`, in a directory
  * named `dfEIP2384`, because the network that released this document at one
  * upgrade carried the figure unchanged into the next one --
  * `ethereum/execution-specs` @ `20f7f6271` `forks/berlin/fork.py:70` is
  * `BOMB_DELAY_BLOCKS = 9000000`, identical to `forks/muir_glacier/fork.py:65`.
  * So a reader looking for this document's cases under the label its release
  * carries finds none, and concludes it is uncertified.
  * `org.fukuii.consensus.pow.certification.DifficultyCorpus` states the same
  * mapping from the harness side.
  */
object Eip2384:

  /** The exponential term is measured from a point nine million blocks below the
    * block being settled.
    *
    * Cumulative rather than incremental -- see the type's own note above for why
    * 9,000,000 and not the 4,000,000 this document adds to [[Eip1234]]'s figure.
    */
  val difficultyBombDelay: ConsensusRules => ConsensusRules = _.copy(difficultyBombDelay = BigInt(9000000))

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Eip(2384), rules => rules.copy(consensus = difficultyBombDelay(rules.consensus)))
