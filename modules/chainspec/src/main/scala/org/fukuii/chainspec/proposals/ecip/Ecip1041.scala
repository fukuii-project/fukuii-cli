package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** ECIP-1041 -- from one height onward the exponential term is not computed at
  * all.
  *
  * ==The document states the height in prose and its code only names it==
  *
  * Its abstract proposes *"removing Difficulty Bomb from Ethereum Classic
  * Mainnet at block 5,900,000"*, and its specification reaches the same figure
  * by argument, bounding the change between an earliest safe block of
  * 5,700,000 and a latest of 6,100,000 before concluding *"It's proposed here
  * to use block 5,900,000 which is in the middle between these boundaries"*
  * (`ethereumclassic/ECIPs` @ `8dda72c24`, `_specs/ecip-1041.md`, status
  * Final, titled *"Remove Difficulty Bomb"*). Its implementation block names
  * the height as a symbol and never assigns it, so the figure below is the
  * prose's and the block is the shape's.
  *
  * ==The document composes over what precedes it, and says so twice==
  *
  * Its implementation is a branch whose second arm defers to whatever was
  * already in force -- *"if (block.number >= diffuse_block) { extra_difficulty
  * = 0 } else { explosion = PREVIOUS_FORMULA ... }"* -- and its specification
  * names what that formula is on this network: *"It was paused by ECIP-1010
  * from block 3,000,000 to block 5,000,000"*. **So the removing document is
  * itself the authority for the window surviving below the removal**, and a
  * delta that cleared the window here would have this network repeal a proposal
  * its own successor cites as still answering.
  *
  * Nothing in this file arranges that. [[org.fukuii.chainspec.UpgradeRules]]
  * carries a rule set forward and each delta rewrites the members it names, so
  * composition is what happens unless a delta prevents it. What is worth
  * knowing is that the composition is REACHABLE rather than merely intact: a
  * height below the removal reads the surviving window and answers a term no
  * reading without it produces. This network's own difficulty vectors state
  * sixteen heights under this upgrade, twelve of them below the removal, and
  * **seven of those sixteen answer differently with the window than without
  * it** -- so a rule set that dropped it here would be refuted rather than
  * merely incomplete.
  *
  * ==Its closing line restates rules already in force and is not a second
  * delta==
  *
  * The implementation block ends with `block_diff = parent_diff + parent_diff /
  * 2048 * max(1 - (block_timestamp - parent_timestamp) / 10, -99) +
  * extra_difficulty`. Both of those figures are members this network already
  * holds: the divisor is
  * [[org.fukuii.chainspec.ConsensusRules.difficultyBoundDivisor]] at the value
  * it launched with, and the clamped quotient is EIP-2's graduated adjustment,
  * adopted 4,750,000 blocks earlier. **The restatement also drops the minimum
  * difficulty every implementation of it applies**, which is what marks the
  * line as a sketch of the whole rule rather than a statement of the change --
  * `besu-eth/besu-etc` @ `eb4248c997` clamps at `MINIMUM_DIFFICULTY` inside the
  * very calculator this document selects.
  *
  * ==Three lineages state it as a height, and one of them chooses a different
  * place to branch==
  *
  * `ethereumclassic/core-geth` @ `4185df450` carries `DisposalBlock:
  * big.NewInt(5900000)` in `params/config_classic.go`, and
  * `openethereum/openethereum` @ `v3.0.1` carries `"bombDefuseTransition":
  * "0x5a06e0"` under `engine.Ethash.params` in
  * `ethcore/res/ethereum/classic.json`. Both hold it beside the window
  * [[Ecip1010]] writes and branch on the two inside one difficulty function.
  *
  * **`besu-eth/besu-etc` @ `eb4248c997` selects instead**, and its selection is
  * the clearest evidence that the composition above is a representation choice
  * rather than a disagreement: `ClassicProtocolSpecs.defuseDifficultyBombDefinition`
  * builds on `gothamDefinition` and swaps its difficulty calculator for
  * `ClassicDifficultyCalculators.DIFFICULTY_BOMB_REMOVED`, which has no
  * exponential term in it at all. Below the fork that client's schedule still
  * resolves `DIFFICULTY_BOMB_DELAYED`, which is ECIP-1010's resumed branch
  * carrying the same window. Its chain configuration names the height
  * `"ecip1041Block": 5900000` in `config/src/main/resources/classic.json`.
  *
  * ==This delta writes one member of one facet==
  *
  * The document settles where the difficulty target's exponential term stops
  * being computed and nothing about the machine, about what admits a
  * transaction, or about what settling one does. All three implementations put
  * it where they put the rest of their difficulty parameters: core-geth beside
  * `ECIP1010PauseBlock` in one chain configuration, OpenEthereum inside
  * `engine.Ethash.params` and apart from that file's `params` block, and
  * besu-etc on a difficulty calculator alone.
  */
object Ecip1041:

  /** The exponential term is not computed at block 5,900,000 or above.
    *
    * **The height is inclusive**, which is the document's own comparison
    * rather than this delta's reading of it: its outer branch is
    * `block.number >= diffuse_block`, so the figure written here is the first
    * block carrying no term. Both implementations holding it as a parameter
    * compare the same way --
    * [[org.fukuii.chainspec.ConsensusRules.difficultyBombRemovedFrom]] carries
    * that evidence, and `org.fukuii.consensus.pow.EthashEngine` asks the
    * question before it reaches any rule that would move the term instead.
    *
    * **Absent rather than zero on a network without the rule.** Zero is a
    * height like any other, and a network stating it would carry no
    * exponential term from its genesis block.
    */
  val difficultyBombRemoval: ConsensusRules => ConsensusRules =
    _.copy(difficultyBombRemovedFrom = Some(BigInt(5900000)))

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Ecip(1041), rules => rules.copy(consensus = difficultyBombRemoval(rules.consensus)))
