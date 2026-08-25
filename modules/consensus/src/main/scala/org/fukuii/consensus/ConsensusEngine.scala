package org.fukuii.consensus

import scala.annotation.unused

import org.fukuii.bytes.Address
import org.fukuii.chainspec.{ConsensusRules, UpgradeRules}
import org.fukuii.evm.{Word, WorldState}
import org.fukuii.types.BlockHeader

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
    * The default credits the beneficiary from [[ConsensusRules]] alone and
    * reads neither of the block facts beside it, and that is not a
    * simplification -- besu shares one `rewardCoinbase` between its mainnet and
    * its Clique specifications and gives Clique its behavior entirely through
    * the two values on the specification. **An engine whose emission is a
    * formula over the resolved amount overrides this**, which is what besu's
    * own `ClassicBlockProcessor` does to reach an era-based schedule.
    *
    * ==Why the block's own facts are on the neutral seam rather than on a
    * mechanism's leaf==
    *
    * Because every surveyed client puts them on the seam every engine
    * implements, rather than on the one mechanism that reads them.
    * `ethereum/go-ethereum-pow` @ `v1.10.26` declares
    * `Finalize(chain, header, state, txs, uncles)` on `consensus.Engine`
    * itself, which Clique implements and ignores;
    * `besu-eth/besu` @ `c2addd9424` declares
    * `rewardCoinbase(worldState, header, ommers, skipZeroBlockRewards)` on
    * `AbstractBlockProcessor`; `NethermindEth/nethermind` @ `c35ce1b1ab` passes
    * a whole `Block` into `IRewardCalculator.CalculateRewards`. A mechanism
    * that credits nobody for an ommer is handed the ommers and does not read
    * them, which is the same shape as an engine handed rules it does not
    * transform.
    *
    * **The two are annotated because this body is what does not read them, and
    * that is the whole content of the annotation.** They are the seam's, and
    * every proof-of-work engine reads both. Suppressing at the site rather than
    * relaxing the category is what `.claude/rules/scala3-style.md` asks for,
    * and the leading-underscore convention is not an alternative here: measured
    * against the pinned compiler, a parameter named `_b` is reported exactly as
    * one named `b`.
    *
    * @param beneficiary
    *   whom the mechanism credits. It arrives as a parameter rather than being
    *   read from a header because which account it is, is the engine's answer
    *   in every surveyed client -- besu asks a `MiningBeneficiaryCalculator` and
    *   the go-ethereum line asks `Engine.Author` -- and a header field is only
    *   the answer for the mechanisms that do not redirect it.
    * @param number
    *   the height of the block being settled. A height rather than a header,
    *   because the height is the whole of what an emission reads about the
    *   block itself, and a header here would offer a beneficiary field beside
    *   the parameter above that is the answer only sometimes.
    * @param ommers
    *   the headers this block included. Headers rather than a reduced pair,
    *   because the two facts an emission reads off one -- its height and the
    *   account credited for it -- are read straight off the header in every
    *   surveyed client. besu takes `ommerHeader.getCoinbase()`, the go-ethereum
    *   line takes `uncle.Coinbase`, `openethereum/openethereum` @ `v3.0.1`
    *   takes `u.author()`, and `ethereum/execution-specs` @ `ccaaaba58` takes
    *   `ommer.coinbase`. **An ommer's beneficiary is not redirected the way the
    *   block's own is**, which is why one of the two arrives as a parameter and
    *   the other does not.
    */
  def settlement(
      rules: ConsensusRules,
      beneficiary: Address,
      @unused number: BigInt,
      @unused ommers: Seq[BlockHeader]
  ): WorldState => Unit =
    world => credit(world, rules, beneficiary, rules.blockReward.toBigInt)

  /** Adds `amount` to what `to` already holds, bringing the account into being
    * where none existed -- unless the amount is nothing and this network does
    * not bring an account into being for a credit of nothing.
    *
    * ==The question and the write are one member, so that a mechanism cannot
    * reach the second without the first==
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
    * **A seam offering the write on its own offers every mechanism a leaf its
    * network does not have, and nothing reports it.** An account never touched
    * and one credited nothing answer the same balance, so an emission that
    * omitted the question satisfies every assertion about what an account holds
    * and parts from the chain only in the state root. There is therefore no
    * ungated write here for a mechanism to reach, and asking is not something
    * an engine can decline to do.
    *
    * ==It is asked of the amount this writes, not of the amount a fork
    * resolved==
    *
    * The two part wherever an emission is a formula rather than the figure
    * itself: an amount stepped down over eras reaches nothing from a resolved
    * reward that is not zero, and a share taken over such an amount is nothing
    * as well. besu asks it once, of the resolved figure, and reaches the same
    * outcome from the other side -- a resolved reward of nothing leaves nothing
    * for a share of it to be.
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
    *
    * **Both ends are refused, and the floor is the one this seam's own
    * reasoning demanded.** `BigInt.mod` answers a non-negative value, so a
    * total below zero does not wrap to a small balance the way an excess wraps
    * -- it wraps to one just under the ceiling, which is the same defect
    * arriving at the other end and a worse figure to find in a state trie. The
    * paragraph above names an amount driving a balance past what it can hold;
    * an amount driving it below nothing is that same broken precondition, and a
    * guard reading only the ceiling refuses one and not the other. **A
    * mechanism expressing a debit as a negative credit is the reachable
    * shape**, and it is why this is refused on the seam rather than in whichever
    * mechanism first writes one.
    */
  final protected def credit(world: WorldState, rules: ConsensusRules, to: Address, amount: BigInt): Unit =
    if amount != 0 || rules.zeroRewardCreditsBeneficiary then
      val credited = world.balanceOf(to).toBigInt + amount
      if credited < 0 || credited > Word.MaxValue.toBigInt then
        throw new IllegalStateException(
          "a block reward moved " + to.toString + " to a balance no account can hold: " + credited.toString
        )
      world.setBalance(to, Word(credited))

object ConsensusEngine:

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
