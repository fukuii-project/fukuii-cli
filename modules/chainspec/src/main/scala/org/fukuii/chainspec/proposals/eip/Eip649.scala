package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** EIP-649 -- two numbers on the consensus facet, held apart on purpose.
  *
  * ==One document, two changes, and its own abstract says why they ship
  * together==
  *
  * The specification has two headed sections. The first replaces the height the
  * exponential term is measured from with *"`fake_block_number = max(0,
  * block.number - 3_000_000)`"*; the second sets *"`new_block_reward =
  * 3_000_000_000_000_000_000`"* (`ethereum/EIPs` @ `dbfa6bee8`,
  * `EIPS/eip-649.md`, Final).
  *
  * Neither follows from the other. What binds them is a policy the document
  * states rather than a mechanism: holding the term back raises issuance, so
  * *"a block reward reduction that offsets the ice age delay would leave the
  * system in the same general state as before"*. **A network is free to take
  * either alone**, and one does -- `ethereumclassic/core-geth` @ `4185df450`
  * removes the term outright at `DisposalBlock` rather than delaying it, and
  * replaces the fixed emission from `ECIP1017FBlock` rather than reducing it
  * once. That is why the two deltas below are separate values, even though this
  * component adopts both.
  *
  * ==The two figures, from sources that do not derive from one another==
  *
  * `ethereum/execution-specs` @ `20f7f6271` declares `BLOCK_REWARD = U256(3 *
  * 10**18)` and `BOMB_DELAY_BLOCKS = 3000000` in `forks/byzantium/fork.py`,
  * against `forks/spurious_dragon/fork.py`, which declares `U256(5 * 10**18)`
  * and no delay constant at all, and `forks/constantinople/fork.py`, which
  * declares `U256(2 * 10**18)` and `5000000`. `ethereumclassic/core-geth` @
  * `4185df450` declares `EIP649FBlockReward = uint256.NewInt(3e+18)` and
  * `EIP649DifficultyBombDelay = uint256.NewInt(3000000)` in
  * `params/vars/protocol_params.go`, keyed by this document's number rather
  * than by any network's name for it.
  *
  * ==What the document restates and this file therefore owes no delta for==
  *
  * It gives a formula for an included block's producer and one for the
  * including block's, and says of each that it is *"the existing pre-Metropolis
  * formula ... simply adjusted with `new_block_reward`"*. Both are already
  * arithmetic over whatever amount a rule set carries --
  * [[org.fukuii.chainspec.ConsensusRules.blockReward]] states that the record
  * holds the figure and the mechanism holds the formulas -- so moving the base
  * moves all three payments together, and a delta restating either formula
  * would be a second place for one number to live.
  */
object Eip649:

  /** Three ether, expressed in wei because that is the unit a balance is held
    * in.
    */
  private val reducedReward: UInt256 =
    UInt256
      .fromBigInt(BigInt(3) * BigInt(10).pow(18))
      .getOrElse(throw new IllegalStateException("three ether does not fit a 256-bit quantity"))

  /** What the mechanism credits for producing a block drops to three ether.
    *
    * **This delta moves the base and nothing else.** The document changes no
    * share and no divisor: what an ommer's producer and an including block's
    * producer receive are the fractions already in force, taken over this
    * figure.
    */
  val blockRewardReduction: ConsensusRules => ConsensusRules = _.copy(blockReward = reducedReward)

  /** The exponential term is measured from a point three million blocks below
    * the block being settled.
    *
    * ==The figure is the document's own, applied to the block rather than to
    * its parent==
    *
    * The specification writes the delay against `block.number`, and
    * [[org.fukuii.chainspec.ConsensusRules.difficultyBombDelay]] is read against
    * the same height, so this is the stated figure unadjusted. A client that
    * computes from the parent instead has to carry it one lower:
    * `ethereum/go-ethereum-pow` @ `v1.10.26` subtracts one inside
    * `makeDifficultyCalculator` with the reason in a comment beside it, and
    * `besu-eth/besu-etc` @ `eb4248c997` declares `2_999_999` outright, as one
    * of six such offsets. Both of those clients carry the figure already
    * decremented; this record carries the one the document states, and the
    * mechanism it is read by is what decides which of the two is right.
    *
    * The document's own floor at zero is the mechanism's, not this record's:
    * a delay is a quantity of blocks, and what happens below it is arithmetic.
    */
  val difficultyBombDelay: ConsensusRules => ConsensusRules = _.copy(difficultyBombDelay = BigInt(3000000))

  /** Adopting the document, which is adopting both of its changes.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    *
    * The order is the order the two compose in. It is immaterial -- they name
    * different fields of one facet -- and it is stated because two deltas
    * touching one field compose to whichever ran last.
    */
  val component: Component =
    Component(
      ProposalId.Eip(649),
      rules => rules.copy(consensus = difficultyBombDelay(blockRewardReduction(rules.consensus)))
    )
