package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ConsensusRules, DifficultyAdjustment, ProposalId}

/** EIP-100 -- what a difficulty target counts, and nothing else.
  *
  * ==One line of the published formula, carrying two changes==
  *
  * The document's specification replaces a single line and states both forms.
  * What it replaces is *"`adj_factor = max(1 - ((timestamp -
  * parent.timestamp) // 10), -99)`"*; what it puts there is *"`adj_factor =
  * max((2 if len(parent.uncles) else 1) - ((timestamp - parent.timestamp) //
  * 9), -99)`"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-100.md`, Final).
  *
  * Two figures move inside that one line -- the term the elapsed gap is
  * subtracted from, and the interval that gap is measured in -- and neither is
  * a member a rule set carries. Both belong to the algorithm this delta
  * selects, so the delta names an algorithm and sets no number at all.
  * [[org.fukuii.chainspec.DifficultyAdjustment]] carries the evidence for
  * enumerating the algorithm rather than parameterizing one, and
  * `org.fukuii.consensus.pow.EthashEngine` holds the arithmetic.
  *
  * ==The parent's ommers enter as a two-valued term, and the document says
  * why==
  *
  * Its rationale writes out the exact formula, *"`adj_factor = max(1 +
  * len(parent.uncles) - ((timestamp - parent.timestamp) // 9), -99)`"*, calls
  * it mathematically equivalent to treating a block with `k` ommers as `k+1`
  * blocks sharing a timestamp, and then declines it: *"since the exact formula
  * depends on the full block and not just the header, we are instead using an
  * approximate formula that accomplishes almost the same effect but has the
  * benefit that it depends only on the block header (as you can check the uncle
  * hash against the blank hash)"*.
  *
  * **So the approximation is what makes the rule answerable from a header
  * alone.** The executable specification takes that reading literally, declaring
  * `parent_has_ommers: bool` on `calculate_block_difficulty` from
  * `forks/byzantium/fork.py` and from no earlier fork module
  * (`ethereum/execution-specs` @ `20f7f6271`, zero occurrences in
  * `forks/homestead/fork.py` and `forks/spurious_dragon/fork.py`, five in
  * `forks/byzantium/fork.py`). `org.fukuii.consensus.pow.EthashEngine`'s
  * `difficulty` records what taking the fact as a parameter costs and what
  * would end it.
  *
  * ==It leaves the exponential term alone, and the document adopted beside it
  * does not==
  *
  * The line above is the whole of this document's specification. It names no
  * delay, no fake block number and no bomb; [[Eip649]] is what moves the
  * exponential term, and the two are separate components here because a
  * production network takes one without the other.
  * `ethereumclassic/core-geth` @ `4185df450` is that network's configuration:
  * `config_classic.go` sets `EIP100FBlock` at 8,772,000 and carries no EIP-649
  * gate at all, while that client's parameter table declares
  * `EIP649FBlockReward` and `EIP649DifficultyBombDelay` behind a gate of their
  * own. A component pairing the two would make the rule set that network runs
  * inexpressible.
  */
object Eip100:

  /** The continuous adjustment is replaced by one that raises its term where
    * the parent included ommers, over a shorter interval.
    *
    * **This delta selects the algorithm and moves no other member.** The
    * document states no change to the bound divisor and none to the exponential
    * term, and the three surveyed clients that gate on its number switch the
    * multiplier alone -- `NethermindEth/nethermind` @ `c35ce1b1ab` on
    * `IsEip100Enabled`, `ethereumclassic/core-geth` @ `4185df450` on
    * `GetEthashEIP100BTransition`, and `openethereum/openethereum` @ `v3.0.1`
    * on `eip100b_transition`.
    */
  val difficultyAdjustment: ConsensusRules => ConsensusRules =
    _.copy(difficultyAdjustment = DifficultyAdjustment.Eip100)

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Eip(100), rules => rules.copy(consensus = difficultyAdjustment(rules.consensus)))
