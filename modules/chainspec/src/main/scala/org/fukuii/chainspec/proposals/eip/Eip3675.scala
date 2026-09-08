package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.{Component, ConsensusRules, HeaderConstants, HeaderRules, ProposalId}

/** EIP-3675 -- the consensus mechanism a block is produced under stops being
  * proof of work.
  *
  * ==What this build adopts, and what the same document defers==
  *
  * The document is much larger than its delta here, and saying which half is
  * which is the first thing a reader needs. Its own abstract lists four areas:
  * *"the set of changes to the block structure, block processing, fork choice
  * rule and network interface introduced by the consensus upgrade"*
  * (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26), `EIPS/eip-3675.md:19`, Final).
  *
  * **The first two are execution-layer rules and are adopted here.** Block
  * structure is [[HeaderConstants.Eip3675]]; the part of block processing that
  * changes a state root is the reward, below.
  *
  * **The last two are not this build's and not this section's.** The fork choice
  * rule moves to a driver outside the execution layer, and the network interface
  * is the engine API that driver speaks over. Neither alters a state root, so
  * neither is expressible as a delta over a rule set at all -- there is no field
  * here they would write. A reader looking for the transition trigger will not
  * find it either: it was a condition on accumulated work rather than a height,
  * and [[org.fukuii.chainspec.Upgrade.RetrospectiveRuleChange]] is where this
  * build records what that costs.
  *
  * ==Three header fields become constants==
  *
  * *"Beginning with `TRANSITION_BLOCK`, a number of previously dynamic block
  * fields are deprecated by enforcing these values to instead be constants"*
  * (`:79`), over a table at `:83-87` with five rows. Three of the five are
  * checkable on a header alone and are what [[HeaderConstants.Eip3675]] carries.
  *
  * **The fourth row is `mixHash`, fixed to zero here and unfixed by EIP-4399**,
  * which this document points at by number in the same section, six lines under
  * its own table: *"Subsequent EIPs may override the constant values specified
  * above to provide additional functionality. For an example, see EIP-4399"*
  * (`:93`). So the two documents do not conflict, and the resolution is not an
  * inference from adjacency -- see [[Eip4399]], which carries it.
  *
  * **The fifth row is the ommers list itself, which is a body and not a
  * header.** `ethereum/execution-specs` @ `20f7f6271a` (2026-08-26) checks it
  * one line above the header validation it belongs beside --
  * `src/ethereum/forks/paris/fork.py:172`, `if block.ommers != (): raise
  * InvalidBlock`, replacing the `validate_ommers` call the fork below it makes.
  * **Nothing in this build holds a block body**, so that check has no home here
  * yet; the header commitment this does adopt is what a node compares first, and
  * the body check is what catches a header committing to the empty list while
  * carrying ommers anyway.
  *
  * ==The reward is removed rather than set to zero, and those are different
  * state roots==
  *
  * The document says *remove*, three times and never *zero*: *"Remove increasing
  * the balance of the block's `beneficiary` account by the block reward"*, and
  * the same for the two ommer rewards (`:115-118`).
  * [[org.fukuii.chainspec.ConsensusRules]] already carries the pair that
  * separates the two readings and records why -- an amount of zero that still
  * credits brings an empty account into being and commits it to the state trie,
  * where a removed credit touches no account at all. **So this adopts both
  * halves**, and adopting only the amount would be a state-root divergence that
  * no reading of the figure alone reveals.
  *
  * `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) writes exactly that pair at its
  * own definition of this fork, `MainnetProtocolSpecs.parisDefinition`:
  * `.blockReward(Wei.ZERO)` and `.skipZeroBlockRewards(true)`.
  * `ethereum/execution-specs` @ `20f7f6271a` reaches it as an absence --
  * `BLOCK_REWARD` occurs four times in `src/ethereum/forks/gray_glacier/` and
  * **zero** times anywhere in `src/ethereum/forks/paris/`, the fork module having
  * dropped the constant and every use of it.
  *
  * ==What is deliberately NOT written, and each is refused for its own reason==
  *
  * **The difficulty members.** This document removes *"verification of the
  * block's `difficulty` value with respect to the difficulty formula"* (`:99`)
  * rather than replacing the formula, so there is no new algorithm for
  * [[org.fukuii.chainspec.DifficultyAdjustment]] to name and no figure for a
  * delay to move. The members [[org.fukuii.chainspec.networks.ethereum.Upgrades]]
  * carries into this composition are therefore left exactly as the fork below it
  * resolved them, which is also what besu does -- its `parisDefinition` builds on
  * `grayGlacierDefinition()` and overrides no delay.
  *
  * **A fourth [[org.fukuii.chainspec.DifficultyAdjustment]] case for a constant
  * zero.** besu has one, `MainnetDifficultyCalculators.PROOF_OF_STAKE_DIFFICULTY
  * = (time, parent) -> BigInteger.ZERO`, and it is not evidence for a case here.
  * That enum selects among published targeting algorithms over a parent, and a
  * constant is not one; what makes a header under these rules valid is the
  * constant comparison [[HeaderConstants.Eip3675]] gates, which besu also does
  * separately and in the header validator rather than in the calculator -- its
  * merge header-validation builder carries `NoDifficultyRule` and no formula rule
  * at all. A calculator answering zero is what a client needs to PRODUCE a block,
  * and this build produces none.
  *
  * **Reversing trigger: this build produces blocks, or an engine resolves a
  * difficulty from these rules for a network that does not mine.** Either makes
  * the constant a value something reads, which is the test
  * [[org.fukuii.chainspec.UpgradeRules]] states for admitting a member at all.
  *
  * **A flag saying which mechanism produces the block.** Two clients carry one --
  * `NethermindEth/nethermind` @ `b92e2a471` (2026-08-26) sets
  * `spec.IsPostMerge = true` and besu `.isPoS(true)` -- and both use it to select
  * an engine rather than to decide a rule. Which engine a network runs at a
  * height is `org.fukuii.consensus.ConsensusEngine`'s question, and answering it
  * twice is how the two answers come to disagree.
  *
  * ==nethermind is NOT a model for this document's rule content==
  *
  * Recorded because it is the client whose fork-resolved record most resembles
  * this build's, so it is the first place a reader would look. Its
  * `MainnetSpecProvider.cs:24` sets `ParisBlockNumber = 15_537_393` -- **one
  * below** the height the executable specification states -- and registers its
  * Paris specification there, with `postMergeBlock: ParisBlockNumber + 1` at
  * `:75`. So its specification for this fork is in force at the last block that
  * was mined, and its own source says what follows: the uncle ban lives on the
  * fork above, because pinning it at this one *"would spec-reject a
  * consensus-valid PoW block if it carried uncles"*
  * (`Nethermind.Specs/Forks/15_Paris.cs`). **That is a client's arrangement of
  * its own ladder and not a reading of the document**, and copying either the
  * height or the placement would import a mixed-era rule set.
  */
object Eip3675:

  /** The three header fields this document holds at a constant.
    *
    * The constants themselves are not here, for the reason
    * [[org.fukuii.chainspec.HeaderConstants]] gives: they are the same on every
    * network that adopts this document, so the fork resolves whether the rule is
    * in force and `org.fukuii.consensus.HeaderValidator` states the figures.
    */
  val constantFields: HeaderRules => HeaderRules = _.copy(constants = HeaderConstants.Eip3675)

  /** The producer is credited nothing, and is not brought into being by being
    * credited nothing.
    *
    * Both members, because the document says *remove* rather than *zero* and the
    * two are different state roots.
    */
  val unrewarded: ConsensusRules => ConsensusRules =
    _.copy(blockReward = UInt256.Zero, zeroRewardCreditsBeneficiary = false)

  /** Adopting the document, which is adopting both of its deltas.
    *
    * Built from the general constructor rather than the machine-scoped one:
    * neither half reaches the machine, and the part of this upgrade that does is
    * [[Eip4399]].
    */
  val component: Component =
    Component(
      ProposalId.Eip(3675),
      rules =>
        rules.copy(
          consensus = unrewarded(rules.consensus),
          header = constantFields(rules.header)
        )
    )
