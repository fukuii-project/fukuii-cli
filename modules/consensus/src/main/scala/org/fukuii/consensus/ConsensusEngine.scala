package org.fukuii.consensus

import scala.annotation.unused

import org.fukuii.bytes.Address
import org.fukuii.chainspec.{ConsensusRules, HeaderConstants, UpgradeRules}
import org.fukuii.evm.{Word, WorldState}
import org.fukuii.execution.Withdrawals
import org.fukuii.types.{BlockHeader, Withdrawal}

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
  * ==The transformation defaults to contributing nothing, and that is the
  * field's design rather than a convenience==
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
  * **Every other member defaults to what the networks this build serves run,
  * not to nothing**, because each is a rule a block breaks or a change it makes
  * on those networks: [[settlement]] credits the resolved reward,
  * [[validateHeader]] bounds the extra data, and [[processWithdrawals]] credits
  * each withdrawal. A mechanism answering differently overrides the member,
  * which is how the field reaches each -- see each member for its evidence.
  *
  * ==What this does not yet carry, and what brings each==
  *
  * A difficulty rule, a seal rule and an ommer ruleset are all engine-shaped on
  * the evidence. **The first two have a place already**: each is a rule over a
  * header, so an engine whose mechanism states one either runs it in an
  * override of [[validateHeader]] or names it there as an [[EngineRule]] it did
  * not run, and [[BlockValidator]] reads both answers. An ommer ruleset arrives
  * with the layer that validates a body's ommers.
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
    * Because all three clients read here put them on the seam every engine
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
    *   in both clients read for it -- `besu-eth/besu` @ `c2addd9424` asks a
    *   `MiningBeneficiaryCalculator` and `ethereum/go-ethereum-pow` @
    *   `v1.10.26` asks `Engine.Author` -- and a header field is only the answer
    *   for the mechanisms that do not redirect it.
    * @param number
    *   the height of the block being settled. A height rather than a header,
    *   because the height is the whole of what an emission reads about the
    *   block itself, and a header here would offer a beneficiary field beside
    *   the parameter above that is the answer only sometimes.
    * @param ommers
    *   the headers this block included. Headers rather than a reduced pair,
    *   because the two facts an emission reads off one -- its height and the
    *   account credited for it -- are read straight off the header in all four
    *   sources read here. `besu-eth/besu` @ `c2addd9424` takes
    *   `ommerHeader.getCoinbase()`, `ethereum/go-ethereum-pow` @ `v1.10.26`
    *   takes `uncle.Coinbase`, `openethereum/openethereum` @ `v3.0.1` takes
    *   `u.author()`, and `ethereum/execution-specs` @ `ccaaaba58` takes
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

  /** The header rules this mechanism settles, checked against the header's
    * parent after the rules every mechanism shares, and the ones among them
    * this engine did not run.
    *
    * ==Three answers, and a refusal outranks both of the others==
    *
    * A `Left` is a rule this engine ran refusing the header. A `Right` is every
    * rule it ran accepting the header, together with the rules its mechanism
    * states that it did not run -- empty where it ran them all. So a rule left
    * unrun can only ever be reported beside a header nothing that did run
    * refused, and [[BlockValidator]] answers a non-empty set as undecided once
    * the block has run and every comparison over it has accepted it.
    *
    * ==A rule may be left unrun only where execution does not read it==
    *
    * Deciding it last is sound only because nothing before it depends on the
    * rule's outcome, so **an engine names as unrun only a rule execution does not
    * read, and runs -- or refuses on -- any rule execution does read, here.**
    * An ethash block's execution reads the difficulty the header states and the
    * outcome of neither rule. A Clique block's is different: its coinbase is its
    * author, and its author is recovered from its seal --
    * `ethereum/go-ethereum` @ `02872e9ef` fills the machine's beneficiary from
    * `Engine().Author(header)` (`core/evm.go:53`) and Clique answers that with
    * `ecrecover` over the seal (`consensus/clique/clique.go:205-206`).
    *
    * **The unrun rules are an answer rather than something a caller chose,
    * which is not how the field expresses a skip.** The clients read that check
    * a seal make skipping it an input, and none answers with the skip.
    * `ethereum/go-ethereum-pow` @ `v1.10.26` takes
    * `VerifyHeader(chain, header, seal bool)` (`consensus/consensus.go:71`), and
    * its snap sync asks for a seal on one header in each hundred until a batch
    * nears its pivot (`eth/downloader/downloader.go:1371-1373`,
    * `core/headerchain.go:333-343`). `besu-eth/besu-etc` @ `eb4248c997` leaves
    * its proof-of-work rule out of light validation
    * (`ProofOfWorkValidationRule.java:136-139`), which its fast sync picks for a
    * header unless a random draw falls under a full-validation rate of a tenth
    * by default (`FastSyncValidationPolicy.java:40-45`,
    * `SynchronizerConfiguration.java:32`). `NethermindEth/nethermind` @
    * `3a98e0818` hands its seal validator a `force` flag and documents the
    * result as *"True if seal is valid or was not checked"*
    * (`Nethermind.Consensus/ISealValidator.cs:17-19`). A caller that chose the
    * input knows what was skipped; a verdict read afterwards cannot, and that
    * last answer is exactly one [[BlockVerdict.Valid]] must never give. The
    * specification skips neither rule: `ethereum/execution-specs` @
    * `0cc100eb1` computes a difficulty and calls `validate_proof_of_work` on
    * every header it validates (`forks/frontier/fork.py:256,269`).
    *
    * **A chain that declares no proof of work -- a fixture sealed without proof,
    * a development network -- is a configuration whose engine states no seal
    * rule**, and the field configures it the same way rather than through a
    * verdict claiming a seal ran. `ethereum/go-ethereum-pow` @ `v1.10.26` builds
    * `NewFaker`, *"a ethash consensus engine with a fake PoW scheme that accepts
    * all blocks' seal as valid, though they still have to conform to the
    * Ethereum consensus rules"* (`consensus/ethash/ethash.go:498-506`).
    * `NethermindEth/nethermind` @ `3a98e0818` registers `NullSealEngine`, which
    * answers both its difficulty and its seal check with `true`, as the seal
    * validator a chain has until a mechanism replaces it
    * (`Nethermind.Init/Modules/BlockProcessingModule.cs:120-121`,
    * `Nethermind.Consensus/NullSealEngine.cs:25-27`). `besu-eth/besu` @
    * `b330564a94` makes the skip a validation mode instead: three light modes
    * each documented *"Skip proof of work validation"*, and `NONE`, *"No
    * Validation. data must be pre-validated"*
    * (`ethereum/core/.../mainnet/HeaderValidationMode.java:18-28`). A node
    * holding the cache for a header's epoch is the other end of the same
    * choice: its engine runs the seal rule rather than naming it.
    *
    * ==An override starts from this answer rather than replacing it==
    *
    * An engine overriding this calls `super.validateHeader`, then removes from
    * the rules it names each one it runs itself and adds any further rule its
    * mechanism states and it does not run -- unless its mechanism states a
    * different rule for extra data, as Clique's structure and QBFT's validator
    * list do, below. An override that drops the call drops the bound, and
    * nothing reports it; one that runs a rule and leaves it named answers every
    * block it accepts as undecided.
    *
    * ==On the seam because the field puts the extra-data rule on the engine==
    *
    * `ethereum/go-ethereum` @ `02872e9ef` checks it inside each engine's own
    * header verification -- `consensus/beacon/consensus.go:213` bounds it at 32
    * bytes, while `consensus/clique/clique.go:261-275` requires a 32-byte vanity
    * and a 65-byte seal, with a signer list between them on checkpoint blocks,
    * which no 32-byte bound admits. `besu-eth/besu` @ `b330564a94` wires one rule
    * into its mainnet and merge header rules, another into Clique's, and no
    * length bound into QBFT's (`QbftBlockHeaderValidationRulesetFactory.java:59-73`),
    * whose extra data carries its validators and commit seals. `paradigmxyz/reth` @ `e63ec720ac`
    * holds the bound as a field of its consensus engine, `max_extra_data_size`
    * (`crates/ethereum/consensus/src/lib.rs:48,209`). So the bound is not a
    * constant of the rules a fork resolves, which
    * [[org.fukuii.chainspec.UpgradeRules]] already records from the other side.
    *
    * ==Run after the shared rules, and the order is a precondition==
    *
    * [[BlockValidator]] reaches this only once [[HeaderValidator]] has accepted
    * the pair, so an override may take succession and the gas figures as
    * settled. A difficulty formula depends on that: it measures the gap since
    * the parent's timestamp and is not defined over a header that does not come
    * after its parent.
    *
    * ==This default names both rules wherever the constants fix neither==
    *
    * Under EIP-3675's header constants neither rule has anything to check: the
    * difficulty and the seal's nonce are fixed values [[HeaderValidator]]
    * already compares, and the seal's other slot holds no proof. So the default
    * names nothing there.
    *
    * **Under constants that fix nothing it names both**, for the extra-data
    * bound's reason: every mechanism read that leaves the constants unfixed
    * states a difficulty rule and a seal rule of its own, so a block validated
    * here was paired with none of them, and answering undecided keeps that
    * visible where answering valid would hide it. Ethash is one; the others
    * read are Clique -- `ethereum/go-ethereum` @ `02872e9ef` refuses a signer
    * outside its snapshot and a difficulty not matching its turn
    * (`consensus/clique/clique.go:472-502`), `besu-eth/besu` @ `b330564a94` adds
    * a `CliqueDifficultyValidationRule` and a `CliqueExtraDataValidationRule`
    * that refuses a proposer, recovered from the seal, outside the validators
    * (`consensus/clique/.../BlockHeaderValidationRulesetFactory.java:103-104`,
    * `CliqueExtraDataValidationRule.java:87-91`), and `NethermindEth/nethermind`
    * @ `3a98e0818` checks both in `CliqueSealValidator.ValidateParams`
    * (`:21-51`); authority round, whose difficulty nethermind derives from the
    * step and whose seal it recovers against the beneficiary
    * (`AuRaSealValidator.cs:124-133,141-164`); and QBFT and IBFT2, which besu
    * gives a constant difficulty and a commit-seal rule
    * (`QbftBlockHeaderValidationRulesetFactory.java:70,73`,
    * `IbftBlockHeaderValidationRulesetFactory.java:71,75`). **The falsifier tried
    * was a chain configured without proof**, and each such configuration read
    * is an engine or a validation mode stating no seal rule, which is what an
    * engine here overrides this to say. Mechanisms outside the families those
    * clients serve were not read.
    *
    * @param parent
    *   unread by this default, which applies a bound to the header alone, and
    *   read by any mechanism whose rule is stated against a parent.
    */
  def validateHeader(block: Resolved, @unused parent: Resolved): Either[HeaderFault, Set[EngineRule]] =
    val length = block.header.extraData.length
    if length > ConsensusEngine.MaximumExtraDataSize then
      Left(HeaderFault.ExtraDataAboveLimit(length, ConsensusEngine.MaximumExtraDataSize))
    else
      block.rules.header.constants match
        case HeaderConstants.Unconstrained => Right(Set(EngineRule.Difficulty, EngineRule.Seal))
        case HeaderConstants.Eip3675       => Right(Set.empty)

  /** What this mechanism writes into state for the withdrawals a block's body
    * carries.
    *
    * ==On the seam because one network family processes them another way==
    *
    * The default is EIP-4895's, a balance credit per withdrawal, which is
    * [[org.fukuii.execution.Withdrawals.credit]]. Gnosis credits no balance:
    * `gnosischain/specs` @ `045d46d6d` `execution/withdrawals.md` has the block
    * make one system call to a withdrawal contract instead, and states that *"If
    * the transaction reverts, or runs out of the gas, the entire block **MUST** be
    * considered invalid."*
    * `NethermindEth/nethermind` @ `3a98e0818` supplies that as its own
    * `AuraWithdrawalProcessor` (`Nethermind.Merge.AuRa/Withdrawals/AuraWithdrawalProcessor.cs:33-65`),
    * and `erigontech/erigon` @ `ab8e9fde7` branches on the engine to reach it
    * (`execution/protocol/rules/merge/merge.go:184-197`). So a validator that
    * credited the withdrawals itself would state one family's rule for every
    * family.
    *
    * **What that network needs beyond an override is not here.** A change to
    * state has nowhere to put the refusal its specification requires, and
    * [[org.fukuii.execution.SystemCall.Target]] names no network-configured
    * contract; both arrive with that engine.
    *
    * @param withdrawals
    *   the list the body carries, which may be empty. [[BlockValidator]] does not
    *   ask at all where the body carries no such field, and the distinction is one
    *   both clients above branch on: nethermind skips the contract call only where
    *   `block.Withdrawals is null`, and erigon only where `withdrawals == nil`.
    */
  def processWithdrawals(withdrawals: Seq[Withdrawal], destroyAccount: Address => Unit): WorldState => Unit =
    world => Withdrawals.credit(withdrawals, world, destroyAccount)

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

/** A header rule a consensus mechanism states, named where an engine did not
  * run it.
  *
  * ==Named after the rules the field already separates==
  *
  * Every client read keeps these two apart. `ethereum/go-ethereum-pow` @
  * `v1.10.26` refuses a wrong difficulty and a wrong seal at different points of
  * one method, the second only when asked (`consensus/ethash/consensus.go:282-287`,
  * `:314-318`); `NethermindEth/nethermind` @ `3a98e0818` gives its seal
  * validator `ValidateParams` and `ValidateSeal`; `ethereum/execution-specs` @
  * `0cc100eb1` calls `calculate_block_difficulty` and `validate_proof_of_work`
  * separately. **Neither name is proof-of-work vocabulary**: `ethereum/go-ethereum`
  * @ `02872e9ef` refuses a Clique header's difficulty as `errWrongDifficulty`
  * and checks its seal in `verifySeal` (`consensus/clique/clique.go:121,472`),
  * and nethermind's `AuRaSealValidator` compares an authority-round header's
  * difficulty with the one its step requires in `ValidateParams` and checks
  * the seal in `ValidateSeal` (`Nethermind.Consensus.AuRa/AuRaSealValidator.cs:124-133,141`).
  */
enum EngineRule:

  /** The difficulty the header states, against the one its parent requires. */
  case Difficulty

  /** The seal the header carries, against what the mechanism accepts as one. */
  case Seal

object ConsensusEngine:

  /** The most extra data a header carries under the mechanisms whose bound is a
    * length.
    *
    * 32 bytes. `ethereum/execution-specs` @ `0cc100eb1` writes
    * `len(header.extra_data) > 32` in every one of its 24 fork modules,
    * `ethereum/go-ethereum-pow` @ `v1.10.26` declares `MaximumExtraDataSize
    * uint64 = 32` (`params/protocol_params.go:27`), and `besu-eth/besu` @
    * `b330564a94` declares `MAX_EXTRA_DATA_BYTES = 32`
    * (`ethereum/core/.../core/BlockHeader.java:37`). A header of exactly this
    * length is accepted: every one of the three refuses on `>`.
    */
  val MaximumExtraDataSize: Int = 32

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
