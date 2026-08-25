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
  * ==The amount is not the emission, and the difference is the whole reason the
  * record is this small==
  *
  * What a block's producer receives is this amount plus a share for each ommer
  * the block included, and what an ommer's producer receives is a share of it
  * scaled by that ommer's age -- and on a network running ECIP-1017, both of
  * those are taken over an amount stepped down once per era rather than over
  * this one. Every one of those is arithmetic over this figure rather than a
  * further figure, so the record holds the figure and
  * `org.fukuii.consensus.pow.EthashEngine` holds the arithmetic.
  *
  * [[Unrewarded]] is therefore not a placeholder for an unwritten emission. It
  * is the value a mechanism that credits nobody resolves to, which is what a
  * proof-of-authority network holds and what Ethereum holds after the merge.
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
  * @param difficultyAdjustment
  *   which published algorithm settles the difficulty of a block whose parent
  *   these rules are read against. [[DifficultyAdjustment]] carries the evidence
  *   for the three cases and for why the selector is a value.
  * @param difficultyBombDelay
  *   how many blocks the exponential term of that algorithm pretends have not
  *   happened.
  *
  *   **This is the one genuinely fork-varying member of the three**, which is
  *   what earns it a place here rather than a constant on the engine.
  *   `ethereum/execution-specs` @ `ccaaaba58` declares `BOMB_DELAY_BLOCKS` as a
  *   per-fork module constant taking seven distinct values and omits it entirely
  *   from the five earliest fork modules, and
  *   `ethereum/go-ethereum-pow` @ `v1.10.26` closes
  *   `makeDifficultyCalculator(bombDelay)` over a literal per fork predicate.
  *
  *   **Singular, against an engine-side schedule that is plural.**
  *   `NethermindEth/nethermind` @ `c35ce1b1ab` names the resolved member
  *   `DifficultyBombDelay` on `IReleaseSpec` while its ethash engine holds a
  *   *schedule* of them and writes the one in force. The compounding that
  *   schedule expresses -- `openethereum/openethereum` @ `v3.0.1` subtracts
  *   every applicable entry of `difficulty_bomb_delays` in turn, and
  *   `ethereumclassic/core-geth` @ `4185df450` comments that its own values are
  *   *"compounding"* rather than pre-compounded -- happens before a fork
  *   resolves, so what a fork answers with is one figure.
  *
  *   Zero is *no delay*, which is what the forks predating the first delay
  *   proposal answer. It is not a sentinel for *no bomb*: a network that removed
  *   the term does not delay it by nothing, and
  *   [[difficultyBombRemovedFrom]] is what expresses that.
  * @param difficultyBombPause
  *   the window, where one is in force, over which that reference point stands
  *   still instead of moving. [[DifficultyBombPause]] carries the evidence for
  *   the pair and for why it is not expressible as a delay.
  *
  *   **Absent rather than a sentinel**, which is what both clients that state
  *   the rule as a parameter do:
  *   `ethereumclassic/core-geth` @ `4185df450` leaves `ECIP1010PauseBlock` nil
  *   on a network without the rule, and `openethereum/parity-ethereum` @
  *   `55c90d401` maps an absent key to `u64::max_value()` so that no height
  *   ever reaches it. A window is either stated or is not; there is no window
  *   of nothing.
  *
  *   **It carries heights, which no other member here does, and that is forced
  *   rather than chosen.** A rule set resolved at a height cannot pre-compute
  *   this one: the same rules are in force inside the window and after it, and
  *   the two compute different terms. So the height a caller asks about is what
  *   selects the branch, and the window has to travel with the rules for it to
  *   be selectable at all.
  * @param difficultyBombRemovedFrom
  *   the first height carrying no exponential term at all, where a network
  *   removed it.
  *
  *   **Separate from a delay because no delay expresses it**, which is
  *   measurable rather than definitional: a delay large enough to floor the
  *   term to nothing at one height floors it at every lower height too, so a
  *   pair of heights either side of a removal boundary -- a term below, none at
  *   or above -- is satisfied by no delay of any size.
  *
  *   Both clients that state it as a parameter carry a height rather than a
  *   flag: `DisposalBlock` in core-geth @ `4185df450`, compared against the
  *   block being settled, and `bomb_defuse_transition` on parity's
  *   `EthashParams` @ `55c90d401`, likewise. `besu-eth/besu-etc` @ `eb4248c997`
  *   is the third and expresses it as a whole calculator with no term in it,
  *   bound to the fork-resolved specification, so the height lives in that
  *   client's schedule rather than in its parameter.
  * @param difficultyBoundDivisor
  *   what the parent's difficulty is divided by to size one step of adjustment.
  *
  *   A resolved parameter in two of the surveyed clients --
  *   `IReleaseSpec.DifficultyBoundDivisor` in nethermind and
  *   `EthashParams.difficulty_bound_divisor` in OpenEthereum -- and a package
  *   constant in the rest. **No network in this project's scope varies it**, so
  *   it is here for the reason nethermind puts it there and not because a fork
  *   has moved it.
  *
  *   It must not be zero. A rule set answering zero has no adjustment step to
  *   state, which is a broken rule set rather than a network's answer.
  */
final case class ConsensusRules(
    blockReward: UInt256,
    zeroRewardCreditsBeneficiary: Boolean,
    difficultyAdjustment: DifficultyAdjustment,
    difficultyBombDelay: BigInt,
    difficultyBombPause: Option[DifficultyBombPause],
    difficultyBombRemovedFrom: Option[BigInt],
    difficultyBoundDivisor: BigInt
)

object ConsensusRules:

  /** The divisor every network in this project's scope sizes an adjustment step
    * by.
    *
    * Two sources that do not derive from one another:
    * `ethereum/execution-specs` @ `ccaaaba58` divides by a literal `2048` in
    * `calculate_block_difficulty` in each of its thirteen fork modules that
    * define one, and `besu-eth/besu-etc` @ `eb4248c99` declares
    * `DIFFICULTY_BOUND_DIVISOR = BigInteger.valueOf(2_048L)` in both
    * `MainnetDifficultyCalculators` and `ClassicDifficultyCalculators`.
    */
  val LaunchBoundDivisor: BigInt = BigInt(2048)

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
    *
    * ==Its difficulty members are the pre-proposal answers, not an absence==
    *
    * A mechanism that credits nobody may still target a difficulty -- a
    * proof-of-authority network fixes one rather than adjusting it -- so this
    * value cannot mean *no difficulty rule* and does not try to. It carries what
    * a network answers before any proposal has changed the algorithm and before
    * any proposal has touched the exponential term, which is the same thing
    * nethermind's uninitialized
    * `ReleaseSpec` carries. **A mechanism that does not adjust difficulty reads
    * none of them**, exactly as a mechanism that credits nobody is handed
    * ommers it does not read.
    */
  val Unrewarded: ConsensusRules =
    ConsensusRules(
      blockReward = UInt256.Zero,
      zeroRewardCreditsBeneficiary = false,
      difficultyAdjustment = DifficultyAdjustment.Original,
      difficultyBombDelay = BigInt(0),
      difficultyBombPause = None,
      difficultyBombRemovedFrom = None,
      difficultyBoundDivisor = LaunchBoundDivisor
    )
