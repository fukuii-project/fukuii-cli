package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment, UpgradeRules}
import org.fukuii.chainspec.proposals.eip.{Eip150, Eip2, Eip7}
import org.fukuii.evm.{EvmRules, GasForwarding, GasSchedule, OpcodeTable, Precompile, PrecompileSet}
import org.fukuii.execution.{AdmissionRules, ExecutionRules}
import org.fukuii.types.TransactionType

/** The configuration Ethereum Classic launched with, and the rule sets it
  * reached from it by adopting proposals.
  *
  * ==Authored here, not referred to from another network==
  *
  * Every value below is written for this network from this network's own
  * sources, and nothing here reads another network's composition. That is a
  * decision rather than an accident, and it is what makes *"these two networks
  * run the same rules"* a claim a test can refute: two configurations built
  * from one value are equal however either was authored, so an assertion over
  * them reports the sharing rather than the agreement.
  *
  * The reuse that does happen is the proposal vocabulary -- `Eip2`, `Eip7` and
  * `Eip150` are one delta each, applied here as they are applied anywhere. That
  * is the field's own division: `besu-eth/besu-etc` @ `eb4248c997` builds this
  * network's gas reprice by inheriting from Ethereum mainnet's *Homestead*
  * definition, re-parenting its graph at the point the two networks part,
  * because inheritance is the only reuse it has. Composing from components
  * needs no parent, so a configuration here references no other network and
  * encodes no claim about which chain continued which.
  *
  * ==Where these numbers come from==
  *
  * `ethereumclassic/core-geth` @ `4185df450364973bbf99efa3923791f5ba40b351`
  * (2025-01-23) is this network's reference implementation and the authority
  * for what it runs. It states the prices as shared constants in
  * `params/vars/protocol_params.go` and `core/vm/gas.go`, and selects which of
  * them a chain runs through per-proposal transitions in its chain
  * configuration: `params/config_classic.go` sets its earliest transition at
  * block 1,150,000, so nothing is repriced before then and the figures below
  * are what this network charged from its first block.
  *
  * The `*Frontier`-suffixed constants there are the pre-reprice readings --
  * `CallGasFrontier` 40, `BalanceGasFrontier` 20, `ExtcodeSizeGasFrontier` 20,
  * `SloadGasFrontier` 50, `ExpByteFrontier` 10 -- and the tiers are
  * `core/vm/gas.go`'s `GasQuickStep` 2 through `GasExtStep` 20.
  *
  * ==No activation and no schedule==
  *
  * A rule set carries neither. Which block each of these starts at is a
  * separate fact, external to this file and to this module's types, and is
  * stated where a schedule is authored.
  */
object Upgrades:

  /** The prices this network launched with, and the floor its later schedules
    * are a change to.
    *
    * Read from `ethereumclassic/core-geth` @ `4185df450`, the constants named
    * in this object's own documentation. Every one of these is a number a
    * network sets for itself; none of them is the machine's.
    *
    * ==Writing these out rather than sharing them is a DEPARTURE from the
    * field==
    *
    * Both surveyed implementations share this table across every chain they
    * serve: core-geth keeps one `params/vars` package and selects behavior with
    * per-proposal transitions, and `besu-eth/besu-etc` @ `eb4248c997` reaches
    * this network's later rules by inheriting from Ethereum mainnet's
    * definitions. Neither writes a second copy.
    *
    * **The reason for departing is that a shared value cannot be disagreed
    * with.** *"These two networks run the same rules"* is a claim this project
    * has to be able to refute, and two configurations built from one value are
    * equal however either was authored -- so an assertion over them would report
    * the sharing and never the agreement. `SharedHistorySpec` is the assertion,
    * and it opens by checking that these are separately built values precisely
    * because everything after that check compares by value.
    *
    * The cost is bounded: both networks' launch configurations are settled
    * history and cannot be repriced, so the two copies cannot drift apart
    * through anything but an edit, which is what the assertion catches.
    *
    * **Reversal trigger: a THIRD network sharing this same launch
    * configuration.** Two copies buy a refutable assertion; three would be
    * paying that cost twice for the same claim, and the shape should be
    * revisited then rather than extended by reflex. A network launching at a
    * later fork is not that trigger -- its prices genuinely differ, so it is
    * not a copy at all.
    */
  val genesisPrices: GasSchedule = GasSchedule(
    base = BigInt(2),
    veryLow = BigInt(3),
    low = BigInt(5),
    mid = BigInt(8),
    high = BigInt(10),
    zero = BigInt(0),
    jumpDest = BigInt(1),
    blockHash = BigInt(20),
    balance = BigInt(20),
    externalBase = BigInt(20),
    storageLoad = BigInt(50),
    storageSet = BigInt(20000),
    storageReset = BigInt(5000),
    refundStorageClear = BigInt(15000),
    refundSelfDestruct = BigInt(24000),
    callBase = BigInt(40),
    callValue = BigInt(9000),
    callStipend = BigInt(2300),
    newAccount = BigInt(25000),
    createBase = BigInt(32000),
    codeDepositPerByte = BigInt(200),
    expBase = BigInt(10),
    expPerByte = BigInt(10),
    keccak256Base = BigInt(30),
    keccak256PerWord = BigInt(6),
    copyPerWord = BigInt(3),
    logBase = BigInt(375),
    logDataPerByte = BigInt(8),
    logTopic = BigInt(375),
    precompileEcRecover = BigInt(3000),
    precompileSha256Base = BigInt(60),
    precompileSha256PerWord = BigInt(12),
    precompileRipemd160Base = BigInt(600),
    precompileRipemd160PerWord = BigInt(120),
    precompileIdentityBase = BigInt(15),
    precompileIdentityPerWord = BigInt(3),
    transactionBase = BigInt(21000),
    transactionDataPerZeroByte = BigInt(4),
    transactionDataPerNonZeroByte = BigInt(68),
    transactionCreate = BigInt(0),
    selfDestruct = BigInt(0),
    selfDestructNewAccount = BigInt(0)
  )

  /** The four natives this network placed at genesis, priced by
    * [[genesisPrices]].
    *
    * Built up from [[PrecompileSet.Empty]] rather than cut down, so the
    * membership is this network's choice rather than a default it failed to
    * override. `ethereumclassic/core-geth` @ `4185df450` reaches the same
    * membership from the other direction: `core/vm/contracts.go` merges these
    * four unconditionally as `basePrecompiledContracts` and gates every later
    * native on a proposal transition, none of which this network had taken.
    */
  val genesisPrecompiles: PrecompileSet =
    PrecompileSet.Empty
      .adding(PrecompileSet.EcRecover, Precompile.EcRecover(genesisPrices.precompileEcRecover))
      .adding(
        PrecompileSet.Sha256,
        Precompile.Sha256(genesisPrices.precompileSha256Base, genesisPrices.precompileSha256PerWord)
      )
      .adding(
        PrecompileSet.Ripemd160,
        Precompile.Ripemd160(genesisPrices.precompileRipemd160Base, genesisPrices.precompileRipemd160PerWord)
      )
      .adding(
        PrecompileSet.Identity,
        Precompile.Identity(genesisPrices.precompileIdentityBase, genesisPrices.precompileIdentityPerWord)
      )

  /** What this network pays the producer of a block, before the era ladder its
    * own proposal applies over it.
    *
    * Five ether, in wei. `openethereum/openethereum` @ `v3.0.1` carries it as
    * `blockReward` in `ethcore/res/ethereum/classic.json`, and
    * `besu-eth/besu-etc` @ `eb4248c99` as
    * `MAX_BLOCK_REWARD = Wei.fromEth(5)`.
    *
    * **It is the base of the ladder rather than a figure superseded by it.**
    * ECIP-1017's first era pays exactly this and its arithmetic is stated as a
    * fraction of it, which is why the amount survives the proposal it is
    * eventually reduced by. besu-etc demonstrates the same relation directly:
    * the block processor it installs to apply the ladder is handed the very
    * `blockReward` the fork resolved.
    */
  val launchReward: UInt256 =
    UInt256
      .fromBigInt(BigInt(5) * BigInt(10).pow(18))
      .getOrElse(throw new IllegalStateException("five ether does not fit a 256-bit quantity"))

  /** The rules this network launched with: the original instruction set at this
    * network's prices, its four precompiles, and no proposal adopted.
    *
    * Adopting nothing is the network's choice being recorded, not an absence of
    * one. `params/config_classic.go` at `4185df450` carries no transition
    * earlier than block 1,150,000, which is that statement in the form its
    * reference implementation writes it.
    *
    * The two settlement rules are both the earlier of their pair, and this
    * network held the first of them back far longer than the one it shares a
    * history with: the same file puts `EIP161FBlock` at 8,772,000.
    *
    * The one format admitted is the shape that predates the envelope. The same
    * file states it in the form its reference implementation writes such
    * things: `EIP2718FBlock`, the transition introducing a typed envelope at
    * all, is set at 13,189,133 -- so no height these rules are in force at
    * carries any other format.
    *
    * ==The emission is five ether, and this network is where the amount and the
    * formula come apart==
    *
    * `openethereum/openethereum` @ `v3.0.1` states it as a scalar in this
    * network's own chain specification, `ethcore/res/ethereum/classic.json`'s
    * `engine.Ethash.params.blockReward`, and `besu-eth/besu-etc` @ `eb4248c99`
    * as `MAX_BLOCK_REWARD = Wei.fromEth(5)`, handed to the fork-resolved
    * specification at `ClassicProtocolSpecs.java:139`. Those are two lineages
    * that do not derive from one another; `ethereumclassic/core-geth` is a
    * third reading and is not counted as independent of `multi-geth`, being the
    * same tree under a later name.
    *
    * **What a rule set can hold is that amount and not this network's
    * schedule.** ECIP-1017 steps the reward down by a fifth on entering each
    * new era and states no last era, so no map from fork to figure expresses
    * it. The field splits the two exactly where this does: besu-etc keeps
    * `blockReward` a value on the fork-resolved specification and installs a
    * block processor that computes over it, and OpenEthereum keeps the scalar
    * in the engine namespace and applies its era function to whatever it reads.
    * `org.fukuii.consensus.pow.EthashEngine` is where the formula is.
    *
    * A reward of zero credits the beneficiary, for the reason it does on any
    * network at a height before touched empty accounts are deleted. It is
    * unobservable at five ether and is stated because the field states it.
    */
  val frontier: UpgradeRules =
    UpgradeRules(
      components = Vector.empty,
      evm = EvmRules(
        table = OpcodeTable.original(genesisPrices),
        schedule = genesisPrices,
        precompiles = genesisPrecompiles,
        gasForwarded = GasForwarding.Whole,
        codeDepositMustSucceed = false
      ),
      execution = ExecutionRules(
        touchedEmptyAccountsAreDeleted = false,
        receiptCarriesStatus = false
      ),
      admission = AdmissionRules(
        admittedTypes = Set(TransactionType.Legacy),
        signatureMayCarryChainId = false,
        signatureSMustBeLow = false
      ),
      consensus = ConsensusRules(
        blockReward = launchReward,
        zeroRewardCreditsBeneficiary = true,
        difficultyAdjustment = DifficultyAdjustment.Original,
        difficultyBombDelay = BigInt(0),
        difficultyBombPause = None,
        difficultyBombRemovedFrom = None,
        difficultyBoundDivisor = ConsensusRules.LaunchBoundDivisor,
        ecip1099Activation = None
      )
    )

  /** [[frontier]] with EIP-7 and EIP-2 adopted.
    *
    * `params/config_classic.go` at `4185df450` states this network's adoption
    * as the two proposals rather than as a fork name: `EIP2FBlock` and
    * `EIP7FBlock`, both at one block. The order the two compose in is immaterial
    * -- the four deltas between them touch disjoint fields -- and it is stated
    * because two deltas touching one field compose to whichever ran last.
    */
  val homestead: UpgradeRules = frontier.adopting(Eip7.component, Eip2.component)

  /** [[homestead]] with EIP-150 adopted.
    *
    * ECIP-1015 is the document by which this network adopted it, and EIP-150 is
    * the rule -- `.claude/rules/nomenclature.md`'s two levels, which this
    * network's two implementations demonstrate by naming the same activation
    * differently: `ecip1015Block` to `besu-eth/besu-etc` @ `eb4248c997`, and
    * `EIP150Block` to `ethereumclassic/core-geth` @ `4185df450`.
    *
    * **This network adopted this proposal on its own schedule and with nothing
    * beside it.** ECIP-1015 proposes one action; the regrouping is one upgrade
    * later, where this network takes EIP-155 with EIP-160 and defers EIP-161
    * and EIP-170 by nearly six million blocks.
    */
  val gasReprice: UpgradeRules = homestead.adopting(Eip150.component)
