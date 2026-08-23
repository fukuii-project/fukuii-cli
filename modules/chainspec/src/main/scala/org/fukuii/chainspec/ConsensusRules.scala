package org.fukuii.chainspec

import org.fukuii.bytes.UInt256

/** What a block owes its consensus mechanism, as values a fork produces rather
  * than a formula the mechanism carries.
  *
  * ==Values here, formulas on the engine, and the field reached that split in
  * four languages==
  *
  * `NethermindEth/nethermind` @ `c35ce1b1ab` declares `UInt256 BlockReward` on
  * `IReleaseSpec.cs:22` -- the fork-resolved rule set -- and supplies the
  * arithmetic separately in `Nethermind.Consensus.Rewards`, whose
  * `RewardCalculator.cs:19` is the whole of the join: `return spec.BlockReward`.
  * `besu-eth/besu` @ `c2addd9424` carries `Wei blockReward` and
  * `boolean skipZeroBlockRewards` on `ProtocolSpec.java` and computes from them
  * in `MainnetBlockProcessor.rewardCoinbase`. `openethereum/openethereum` @
  * `v3.0.1` writes the same two facts as JSON scalars under an engine
  * namespace. Each of the three separates the number from the arithmetic over
  * it, and none of them makes the number a function.
  *
  * That split is what lets this record keep the property
  * [[UpgradeRules]] states of every facet: a member typed as a function or an
  * open interface would make two identically-configured networks compare
  * unequal, and *"do these two networks run the same rules"* is a question this
  * project has to answer.
  *
  * ==The engine may overwrite what a fork resolved, and that is the shape
  * rather than a violation of it==
  *
  * A rule set resolved from the schedule is not the last word. nethermind's
  * `IChainSpecEngineParameters.ApplyToReleaseSpec(ReleaseSpec, ulong, ulong?)`
  * runs an engine's own transformation over a `ReleaseSpec` after the fork
  * ladder has built it, and `EthashChainSpecEngineParameters.cs:58,77` is that
  * interface writing `spec.BlockReward` from a schedule the *engine* holds,
  * selected at the release's own start block. besu spells the same thing as
  * `ProtocolSpecAdapters`, a `Map<Long, Function<ProtocolSpecBuilder,
  * ProtocolSpecBuilder>>` resolved per block.
  *
  * So both authorings are live in one client: nethermind's hardcoded fork
  * ladder varies `spec.BlockReward` at three of its own fork definitions, while
  * its chain-spec path leaves `ReleaseSpec.cs:24` at the uninitialized zero and
  * lets the engine supply the emission. **A record that could only express one
  * of the two would foreclose a network the field already serves**, which is
  * why the value sits here and the transformation sits on
  * [[org.fukuii.consensus.ConsensusEngine]].
  *
  * ==Neither member is a network's emission, and no emission is authored yet==
  *
  * [[Unrewarded]] is what both networks in this build currently hold. It states
  * that nothing is credited, which is not either network's schedule -- those are
  * read from their own proposals, by the layer that computes an emission, and
  * nothing here should be mistaken for them.
  *
  * @param blockReward
  *   what the mechanism credits for producing this block, before any formula
  *   the engine applies over it. `blockReward` is the field's own word for it
  *   with no dissent: `BLOCK_REWARD` in `ethereum/execution-specs` @
  *   `ccaaaba58`, `BlockReward` in nethermind, `blockReward` in besu,
  *   `block_reward` in OpenEthereum, and the `*BlockReward` constants in
  *   `ethereum/go-ethereum-pow` @ `v1.10.26`.
  *
  *   It is [[org.fukuii.bytes.UInt256]] rather than the machine's word because
  *   it is a balance quantity: `Account.balance` is already that type, and the
  *   machine's word wraps where this must not.
  * @param zeroRewardCreditsBeneficiary
  *   whether a reward of zero still credits the beneficiary, bringing the
  *   account into being where none existed.
  *
  *   **The pair exists because "no reward" and "a reward of zero" are different
  *   state roots**, and one boolean beside the amount is the whole of what
  *   separates them. besu @ `c2addd9424` returns from
  *   `MainnetBlockProcessor.rewardCoinbase:78-80` *before* it reaches
  *   `updater.getOrCreate(miningBeneficiary)`, so the account is never touched;
  *   without that return it does `getOrCreate` and then `incrementBalance` of
  *   zero, which brings an empty account into being and commits it to the state
  *   trie. nethermind reaches the same fork as two classes rather than a flag
  *   -- `NoBlockRewards.CalculateRewards` answers an empty array while
  *   `ZeroWeiRewards.CalculateRewards` answers one reward of zero -- and states
  *   the consequence in its own source: *"0 wei accounts are created for block
  *   authors"*.
  *
  *   **The name is this project's and the shape is besu's, which is the
  *   distinction `.claude/rules/reference-first.md` draws.** besu spells it
  *   `skipZeroBlockRewards`, whose true case means *do not credit*; a sweep of
  *   the other surveyed clients finds no second name for it, because nethermind
  *   expresses the choice as which class is registered and OpenEthereum as
  *   whether a JSON key is present. With no field consensus to depart from, this
  *   states what happens rather than what is skipped, so that a reader does not
  *   have to negate the name to reach the behavior.
  */
final case class ConsensusRules(
    blockReward: UInt256,
    zeroRewardCreditsBeneficiary: Boolean
)

object ConsensusRules:

  /** A mechanism that credits nobody, and does not bring a beneficiary into
    * being by crediting it nothing.
    *
    * The safe direction of the two: it touches no account, so it cannot add one
    * to the state trie that the network does not have. nethermind defaults the
    * same way and by construction rather than by choice --
    * `ReleaseSpec.cs:24`'s `BlockReward` has no initializer, and
    * `BlockProcessingModule.cs` registers `NoBlockRewards.Instance` as the
    * default reward source, which the ethash and AuRa plugins override and the
    * Clique plugin does not.
    */
  val Unrewarded: ConsensusRules =
    ConsensusRules(blockReward = UInt256.Zero, zeroRewardCreditsBeneficiary = false)
