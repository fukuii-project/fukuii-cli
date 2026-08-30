package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** EIP-1234 -- the second reward reduction and the second bomb delay.
  *
  * ==The same two-part shape as EIP-649, and the parts stay separate for the
  * same reason==
  *
  * This document does to Byzantium's figures what EIP-649 did to Spurious
  * Dragon's: it lowers what producing a block pays and pushes the exponential
  * term further out. `EIPS/eip-1234.md` (`ethereum/EIPs` @ `dbfa6bee`, Final)
  * states both under one Specification, and a network is still free to take
  * either alone -- `ethereumclassic/core-geth` @ `4185df450` takes neither,
  * having removed the term outright and replaced the emission with ECIP-1017's
  * schedule. So the two deltas below are separate values even though this
  * component adopts both.
  *
  * ==The two figures==
  *
  * *"`new_block_reward = 2_000_000_000_000_000_000 if block.number >=
  * CNSTNTNPL_FORK_BLKNUM else block.reward`"* and *"`fake_block_number =
  * max(0, block.number - 5_000_000) if block.number >= CNSTNTNPL_FORK_BLKNUM
  * else block.number`"*, both from the document's own Specification.
  * `ethereum/execution-specs` @ `20f7f6271a` declares `BLOCK_REWARD = U256(2 *
  * 10**18)` and `BOMB_DELAY_BLOCKS = 5000000` in `forks/constantinople/fork.py`,
  * against `forks/byzantium/fork.py`'s `U256(3 * 10**18)` and `3000000`.
  *
  * ==THE DELAY IS CUMULATIVE AND 5,000,000 IS THE WHOLE OF IT==
  *
  * The document describes itself as *"adjusting around 5 million blocks later
  * than previously specified with the Homestead fork"*, and EIP-649 already set
  * 3,000,000. **The increment is 2,000,000 and the figure this record holds is
  * 5,000,000, not 2,000,000.** Both representations exist in the field and both
  * are right in their own frame: `NethermindEth/nethermind` @ `b92e2a4719`
  * declares `spec.DifficultyBombDelay = 5000000L` in
  * `Forks/07_Constantinople.cs` and core-geth declares
  * `EIP1234DifficultyBombDelay = 5000000`, while both clients' chain-spec
  * layers carry `"0x6f1580": "0x1e8480"` -- 2,000,000 at 7,280,000 -- on top of
  * an earlier 3,000,000, and sum them at load. **This record is the cumulative
  * one**, because [[org.fukuii.chainspec.ConsensusRules.difficultyBombDelay]]
  * is read as a single subtraction rather than accumulated across entries.
  *
  * ==And it is applied to the block, not to its parent==
  *
  * The same caveat EIP-649's delta carries, and for the same reason: the figure
  * here is the one the document states, and a client computing from the parent
  * carries it one lower. Do not "correct" 5,000,000 to 4,999,999 by analogy
  * with a client whose mechanism differs from this one's.
  *
  * ==What the document restates and this file therefore owes no delta for==
  *
  * `new_uncle_reward` and `new_nephew_reward` are given as `(8 - k) *
  * new_block_reward / 8` and `new_block_reward / 32` -- the existing formulas
  * over the new base. They are arithmetic the mechanism already performs over
  * whatever amount a rule set carries, so moving the base moves all three
  * payments together and a delta restating either formula would give one number
  * a second home.
  */
object Eip1234:

  /** Two ether, expressed in wei because that is the unit a balance is held in.
    */
  private val reducedReward: UInt256 =
    UInt256
      .fromBigInt(BigInt(2) * BigInt(10).pow(18))
      .getOrElse(throw new IllegalStateException("two ether does not fit a 256-bit quantity"))

  /** What the mechanism credits for producing a block drops to two ether.
    *
    * **This delta moves the base and nothing else** -- no share and no divisor
    * changes, so what an ommer's producer and an including block's producer
    * receive are the fractions already in force, taken over this figure.
    */
  val blockRewardReduction: ConsensusRules => ConsensusRules = _.copy(blockReward = reducedReward)

  /** The exponential term is measured from a point five million blocks below the
    * block being settled.
    *
    * Cumulative rather than incremental -- see the type's own note above for why
    * 5,000,000 and not the 2,000,000 this document adds to EIP-649's figure.
    */
  val difficultyBombDelay: ConsensusRules => ConsensusRules = _.copy(difficultyBombDelay = BigInt(5000000))

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
      ProposalId.Eip(1234),
      rules => rules.copy(consensus = difficultyBombDelay(blockRewardReduction(rules.consensus)))
    )
