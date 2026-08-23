package org.fukuii.consensus

import org.fukuii.bytes.Address
import org.fukuii.chainspec.{ConsensusRules, UpgradeRules}
import org.fukuii.evm.{Word, WorldState}

/** The consensus mechanism a network runs, as a transformation over the rules a
  * fork resolved and a change to state the block's transactions did not make.
  *
  * ==Why a transformation rather than a second value beside the rules==
  *
  * Because that is the join the field builds, in four languages, and it answers
  * a question a pair of independent values cannot:
  * *"do these two networks running the same forks run the same rules"*.
  * `NethermindEth/nethermind` @ `c35ce1b1ab` gives it a name --
  * `IChainSpecEngineParameters.ApplyToReleaseSpec(ReleaseSpec, ulong, ulong?)`,
  * run by `ChainSpecBasedSpecProvider.CreateReleaseSpec` as the last step
  * before the rule set is returned, over the one rule-set type it builds for
  * every network it ships. `besu-eth/besu` @ `c2addd9424` spells it
  * `ProtocolSpecAdapters`, literally
  * `Map<Long, Function<ProtocolSpecBuilder, ProtocolSpecBuilder>>` resolved per
  * block. `openethereum/openethereum` @ `v3.0.1` writes the same join
  * imperatively, inserting the engine's own transitions into the fork set it
  * has just built.
  *
  * Equality survives it because what compares is the transformation's OUTPUT --
  * an [[org.fukuii.chainspec.UpgradeRules]], which is a record of values --
  * rather than the transformation.
  *
  * ==Both members default to contributing nothing, and that is the field's
  * design rather than a convenience==
  *
  * nethermind's interface supplies a default no-op body for
  * `ApplyToReleaseSpec`, and besu's `getModifierForBlock` answers the identity
  * function where no adapter is registered. **An engine with nothing to
  * contribute supplies a transformation that transforms nothing and is still
  * run**, which is the reasoning
  * [[org.fukuii.execution.BlockProcessor]] already records for a mechanism with
  * nothing to write: a slot that is sometimes absent and sometimes empty
  * collapses two states a chain distinguishes.
  *
  * ==What this does not yet carry, and what brings each==
  *
  * A difficulty rule, a seal rule and an ommer ruleset are all engine-shaped on
  * the evidence and none is here, because nothing reads one. Each arrives with
  * the layer that validates the thing it governs.
  *
  * The transformation is also resolved ONCE here rather than per activation,
  * and the field resolves it per activation: nethermind passes
  * `(startBlock, startTimestamp)` into `ApplyToReleaseSpec`, whose ethash
  * implementation walks the engine's own reward schedule up to `startBlock`,
  * and besu floors a block number into its adapter map and answers
  * `Function.identity()` where no adapter sits at or below it.
  *
  * **An engine also contributes resolution points of its own, which is the
  * half a parameter alone would not supply.**
  * `EthashChainSpecEngineParameters.AddTransitions` adds every block number in
  * the engine's reward schedule and bomb-delay schedule to the set the ladder
  * resolves over, so an emission step becomes a fork boundary the fork list does
  * not otherwise contain. Nothing in this build resolves a schedule against an
  * engine yet, so an activation parameter here would be one nothing varies on;
  * the first caller that composes the two is what brings both.
  */
trait ConsensusEngine:

  /** The rules this engine runs, given what the fork schedule resolved.
    *
    * The identity by default. An engine overrides it to state what its
    * mechanism settles that the fork does not -- besu's Clique schedule
    * overwriting the block reward and the beneficiary calculator is the shape,
    * and nethermind's ethash parameters writing `spec.BlockReward` from a
    * schedule the engine holds is the same shape reaching the same member from
    * the other side.
    */
  def rulesFrom(rules: UpgradeRules): UpgradeRules = rules

  /** What this engine writes into state once the block's transactions are done.
    *
    * Returned as the change rather than as a figure, which is the shape
    * [[org.fukuii.execution.BlockProcessor.process]] takes for
    * `consensusStateChange` and the reason it takes it: a mechanism computing
    * from an unbounded schedule, one calling a contract against state, and one
    * doing nothing at all all compose with a change and only the first two
    * compose with a number.
    *
    * The default credits the beneficiary from [[ConsensusRules]] alone, and
    * that is not a simplification -- besu shares one `rewardCoinbase` between
    * its mainnet and its Clique specifications and gives Clique its behavior
    * entirely through those two values. **An engine whose emission is a formula
    * over the resolved amount overrides this**, which is what besu's own
    * `ClassicBlockProcessor` does to reach an era-based schedule.
    *
    * @param beneficiary
    *   whom the mechanism credits. It arrives as a parameter rather than being
    *   read from a header because which account it is, is the engine's answer
    *   in every surveyed client -- besu asks a `MiningBeneficiaryCalculator` and
    *   the go-ethereum line asks `Engine.Author` -- and a header field is only
    *   the answer for the mechanisms that do not redirect it.
    */
  def settlement(rules: ConsensusRules, beneficiary: Address): WorldState => Unit =
    world => ConsensusEngine.credit(rules, beneficiary, world)

object ConsensusEngine:

  /** Credits `beneficiary` with the reward these rules state.
    *
    * ==The zero case decides whether an account exists, so it is checked before
    * anything is written==
    *
    * `besu-eth/besu` @ `c2addd9424` returns from
    * `MainnetBlockProcessor.rewardCoinbase:78-80` before it reaches
    * `updater.getOrCreate(miningBeneficiary)`; the same method without that
    * return creates the account and increments it by zero. The two leave
    * different state roots, and
    * [[org.fukuii.evm.WorldState.setBalance]] is total in exactly the way that
    * makes the distinction reachable here -- it brings an account into being
    * where none existed, so writing a zero is not the no-op it reads as.
    *
    * ==The arithmetic is arbitrary-precision and bounded before the word is
    * built==
    *
    * [[org.fukuii.evm.Word]] wraps, so a credit past the ceiling would answer a
    * small balance rather than fail, arriving as a plausible figure with nothing
    * to distinguish it from one the chain agreed on. No emission any network
    * schedules approaches the bound, so a caller that reached it supplied a
    * reward no network states -- a broken precondition rather than a state a
    * chain reaches, and raised as one, exactly as
    * [[org.fukuii.execution.TransactionProcessor]] raises its own.
    */
  private def credit(rules: ConsensusRules, beneficiary: Address, world: WorldState): Unit =
    val reward = rules.blockReward.toBigInt
    if reward != 0 || rules.zeroRewardCreditsBeneficiary then
      val credited = world.balanceOf(beneficiary).toBigInt + reward
      if credited > Word.MaxValue.toBigInt then
        throw new IllegalStateException(
          "a block reward moved " + beneficiary.toString + " to a balance no account can hold: " + credited.toString
        )
      world.setBalance(beneficiary, Word(credited))

  /** An engine that contributes nothing of its own to the rules it is handed.
    *
    * The identity transformation and the reward application the values alone
    * describe. **It is what a mechanism whose parameters are entirely the
    * fork's looks like**: besu authors the reward onto its fork-resolved
    * specification and it is the Clique schedule, not the mainnet one, that
    * registers an override over it.
    *
    * A mechanism that does have something to contribute extends
    * [[ConsensusEngine]] and says what, rather than starting from this.
    */
  val Unmodifying: ConsensusEngine = new ConsensusEngine {}
