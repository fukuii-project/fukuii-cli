package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{Component, ConsensusRules, DifficultyBombPause, ProposalId}

/** ECIP-1010 -- the exponential term's reference point stands still for two
  * million blocks and then resumes from where it was suspended.
  *
  * ==The document states a pair of heights and derives the rest from them==
  *
  * Its `Constants` block is `pause_block = 3000000`, `cont_block = 5000000`,
  * `delay = (cont_block - pause_block) / 100000` and
  * `fixed_diff = (pause_block / 100000) - 2`, and its final formula branches on
  * the two heights alone: below `pause_block` the term is
  * `(block.number / 100000) - 2`, below `cont_block` it is `fixed_diff`, and at
  * or above `cont_block` it is `(block.number / 100000) - delay - 2`
  * (`ethereumclassic/ECIPs` @ `f398567f4`, `_specs/ecip-1010.md`, status
  * Final, titled *"Delay Difficulty Bomb Explosion"*).
  *
  * **So the two derived constants are arithmetic over the pair rather than
  * settings of their own**, which is why this delta writes the pair and nothing
  * else. [[org.fukuii.chainspec.DifficultyBombPause]] carries the evidence for
  * holding the two heights as one record and for why no delay expresses them;
  * `org.fukuii.consensus.pow.EthashEngine` recomputes both derivations from
  * that record at the height it is asked about.
  *
  * ==The whole window arrives at once, so the resumption needs no second
  * adoption==
  *
  * `cont_block` sits two million blocks above `pause_block`, and this delta
  * writes both the moment the document is adopted. A reader expecting a
  * resumption to be scheduled will not find one, and its absence is the
  * representation working rather than a rule that was missed: the record
  * travels with the rules and the height being settled selects the branch, so
  * the rule set in force inside the window and the one in force after it are
  * the same value answering differently.
  *
  * The field does not agree on that decomposition, and the readings compute
  * the same term. Two of the three carry the pair as parameters and branch on
  * it inside one difficulty function: `openethereum/openethereum` @ `v3.0.1`
  * holds `ecip1010PauseTransition` `0x2dc6c0` and
  * `ecip1010ContinueTransition` `0x4c4b40` on one engine parameter set and
  * tests the block against both in successive arms of `calculate_difficulty`,
  * deriving its own `fixed_difficulty` there as
  * `(ecip1010_pause_transition / EXP_DIFF_PERIOD) - 2`; and
  * `ethereumclassic/core-geth` @ `4185df450` holds `ECIP1010PauseBlock`
  * 3,000,000 with `ECIP1010Length` 2,000,000 in one chain configuration.
  * **`besu-eth/besu-etc` @ `eb4248c997` selects instead**, installing
  * `DIFFICULTY_BOMB_PAUSED` at its Die Hard definition and
  * `DIFFICULTY_BOMB_DELAYED` at its Gotham one, with `PAUSE_BLOCK`,
  * `CONTINUE_BLOCK` and a `DELAY` derived from their difference as class
  * constants of `ClassicDifficultyCalculators`. That is agreement on the
  * arithmetic with disagreement on where the second branch is chosen, and only
  * the third shape needs an upgrade to carry it.
  *
  * ==This delta writes one member of one facet==
  *
  * The document changes what the difficulty target's exponential term is
  * measured from and settles nothing about the machine, about what admits a
  * transaction, or about what settling one does. `openethereum/openethereum` @
  * `v3.0.1` puts the pair on `engine.Ethash.params` in
  * `ethcore/res/ethereum/classic.json`, beside `bombDefuseTransition` and
  * `difficultyBoundDivisor` and apart from that file's `params` block, which is
  * where its gas and account transitions are; `besu-eth/besu-etc` @
  * `eb4248c997` reaches it through a difficulty calculator alone, its Die Hard
  * definition varying the gas calculator separately and for a different
  * document.
  *
  * ==The number alone does not identify this document, and 1010 is the first
  * case rather than a hypothetical one==
  *
  * `EIP-1010` is *"Uniformity Between
  * 0xAb5801a7D398351b8bE11C439e05C5B3259aeC9B and
  * 0x15E55EF43efA8348dDaeAa455F16C43B64917e3c"*, Stagnant
  * (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-1010.md`), which has nothing to do
  * with a difficulty target. So the first document this package carries is
  * already a collision, and the collision
  * [[org.fukuii.chainspec.ProposalId]] opens on -- `1015` in both series -- is
  * not the only one.
  *
  * **[[org.fukuii.chainspec.ProposalId]] is what tells the two apart, and this
  * directory only mirrors it.** A component is recorded as
  * `ProposalId.Ecip(1010)`, which is unequal to `ProposalId.Eip(1010)` whatever
  * file either was written in, so nothing downstream reads a path to decide
  * which series a proposal belongs to. Filing by series is for a reader; the
  * discriminator is the type.
  */
object Ecip1010:

  /** The exponential term's reference point is held at block 3,000,000 until
    * block 5,000,000, which the term then resumes from.
    *
    * ==Two lineages state the pair and neither derives from the other==
    *
    * `openethereum/openethereum` @ `v3.0.1` carries
    * `ecip1010PauseTransition` `0x2dc6c0` and `ecip1010ContinueTransition`
    * `0x4c4b40` in `ethcore/res/ethereum/classic.json`, and
    * `ethereumclassic/core-geth` @ `4185df450` carries `ECIP1010PauseBlock`
    * 3,000,000 with `ECIP1010Length` 2,000,000 in `params/config_classic.go`.
    * Both read back to the document's own `pause_block` and `cont_block`.
    *
    * **The upper height is exclusive**, which is the record's contract rather
    * than this delta's reading of it: the document's middle branch is
    * `block.number < cont_block`, so the last paused height is the one below
    * the figure written here.
    */
  val difficultyBombPause: ConsensusRules => ConsensusRules =
    _.copy(difficultyBombPause =
      Some(DifficultyBombPause(pausedFrom = BigInt(3000000), continuesFrom = BigInt(5000000)))
    )

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Ecip(1010), rules => rules.copy(consensus = difficultyBombPause(rules.consensus)))
