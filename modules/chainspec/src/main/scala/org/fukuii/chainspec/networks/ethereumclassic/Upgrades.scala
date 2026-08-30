package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.{UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment, UpgradeRules}
import org.fukuii.chainspec.proposals.ecip.{Ecip1010, Ecip1017, Ecip1039, Ecip1041}
import org.fukuii.chainspec.proposals.eip.{
  Eip100,
  Eip140,
  Eip150,
  Eip155,
  Eip160,
  Eip161,
  Eip170,
  Eip196,
  Eip197,
  Eip198,
  Eip2,
  Eip211,
  Eip214,
  Eip658,
  Eip7
}
import org.fukuii.evm.{
  EvmRules,
  GasForwarding,
  GasSchedule,
  NewAccountCharge,
  OpcodeTable,
  Precompile,
  PrecompileSet,
  StorageMetering
}
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
    * Both implementations read here share this table across every chain they
    * serve: `ethereumclassic/core-geth` @ `4185df450` keeps one `params/vars`
    * package and selects behavior with per-proposal transitions, and
    * `besu-eth/besu-etc` @ `eb4248c997` reaches
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
    extCodeHash = BigInt(400),
    storageLoad = BigInt(50),
    storageSet = BigInt(20000),
    storageReset = BigInt(5000),
    refundStorageClear = BigInt(15000),
    netStorageNoop = BigInt(200),
    netStorageInit = BigInt(20000),
    netStorageClean = BigInt(5000),
    netStorageDirty = BigInt(200),
    refundNetStorageClear = BigInt(15000),
    refundNetStorageResetFromZero = BigInt(19800),
    refundNetStorageReset = BigInt(4800),
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
    precompileModExpDivisor = BigInt(20),
    precompileAltBn128Add = BigInt(500),
    precompileAltBn128Mul = BigInt(40000),
    precompileAltBn128PairingBase = BigInt(100000),
    precompileAltBn128PairingPerPoint = BigInt(80000),
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
        codeDepositMustSucceed = false,
        maxCodeSize = None,
        createdAccountNonce = UInt64.Zero,
        newAccountCharge = NewAccountCharge.WhenTheDestinationIsAbsent,
        storageMetering = StorageMetering.Legacy,
        touchSurvivesFailure = Set.empty
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

  /** [[gasReprice]] with EIP-155, EIP-160 and ECIP-1010 adopted.
    *
    * The order is the order the three compose in. It is immaterial -- they
    * write a member of the admission facet, a price in the machine's schedule
    * and a member of the consensus facet, and no two of them name one field --
    * and it is stated because two deltas touching one field compose to
    * whichever ran last.
    *
    * ==Each of the three is sourced separately, and one of the three sources
    * disagrees about one of them==
    *
    * ECIP-1066 -- `ethereumclassic/ECIPs` @
    * `e36ef7f10166769aa3ac469aaf27ba5b0cacb198` (2026-07-05) -- enumerates
    * exactly these three under its `Incl EIPs` heading and no others. **Its
    * remaining two cells for this row, `Specs` and `Blog`, are both empty**, so
    * the deferral below is sourced to the two implementations rather than to
    * that table.
    *
    * **EIP-155 and EIP-160** are stated at one height by two lineages that do
    * not derive from one another: `ethereumclassic/core-geth` @ `4185df450`
    * writes `EIP155Block` and `EIP160FBlock` in `params/config_classic.go`, and
    * `openethereum/openethereum` @ `v3.0.1` writes `eip155Transition` and
    * `eip160Transition` `0x2dc6c0` in `ethcore/res/ethereum/classic.json`.
    * **`besu-eth/besu-etc` @ `eb4248c997` is not a third reading of EIP-155
    * here and must not be cited as one**: its `ClassicProtocolSpecs`
    * definition for the preceding upgrade already carries
    * `.isReplayProtectionSupported(true)`, so that client admits the later
    * signing scheme half a million blocks below where the other two put it. It
    * corroborates EIP-160 -- its Die Hard definition installs a gas calculator
    * overriding the per-byte exponent charge and nothing else -- and it
    * corroborates ECIP-1010, whose calculator that same definition installs.
    *
    * **ECIP-1010** is [[Ecip1010]]'s, sourced there.
    *
    * ==What this network declined here is the difference between the two
    * networks' Spurious Dragon==
    *
    * EIP-607 bundles four proposals and this composition takes two of them.
    * `params/config_classic.go` at `4185df450` puts `EIP161FBlock` and
    * `EIP170FBlock` 5,772,000 blocks higher than the two adopted here, and
    * `openethereum/openethereum` @ `v3.0.1` puts `eip161abcTransition`,
    * `eip161dTransition` and `maxCodeSizeTransition` at that same higher
    * figure. So the two groups are separate rule sets on this network and one
    * composition on the other. A rule set composed from proposals states that
    * by adopting a different list; one derived from a fork's name could not
    * state it at all.
    */
  val dieHard: UpgradeRules = gasReprice.adopting(Eip155.component, Eip160.component, Ecip1010.component)

  /** [[dieHard]] with ECIP-1017 and ECIP-1039 adopted.
    *
    * ==Both are sourced separately, and both are this network's own series==
    *
    * ECIP-1066 -- `ethereumclassic/ECIPs` @
    * `e36ef7f10166769aa3ac469aaf27ba5b0cacb198` (2026-07-05) -- lists exactly
    * these two against this upgrade and nothing else. It lists them in its
    * included-proposals column rather than its specifications column, which is
    * the same division the preceding upgrades are tabulated under: a proposal in
    * the first column is a RULE the upgrade carries, and one in the second is
    * the DOCUMENT by which this network resolved to adopt the upgrade. The gas
    * reprice is the row that shows both at once, holding EIP-150 in the first
    * and ECIP-1015 in the second, and [[gasReprice]] records only the former for
    * that reason. Here the two columns say the same thing they say for
    * [[dieHard]]: both entries are rules, and no separate adoption document is
    * tabulated.
    *
    * **ECIP-1017** is [[Ecip1017]]'s, sourced there, and
    * `ethereumclassic/core-geth` @ `4185df450` corroborates the pair as
    * `ECIP1017FBlock` and `ECIP1017EraRounds` in `params/config_classic.go`.
    * **ECIP-1039** is [[Ecip1039]]'s, sourced there; no chain configuration in
    * either implementation names it, because what it settles is where a
    * division falls rather than a figure a configuration could carry.
    *
    * ==Neither writes a rule, and the composition is still not [[dieHard]]==
    *
    * Both deltas leave every facet as it was, for the reasons their own
    * documents give -- the era ladder is computed from an era length the engine
    * carries, and the roundings are positions inside that computation. So this
    * rule set and the one it is built from hold the same values and differ in
    * what they record having adopted, which is the distinction
    * [[org.fukuii.chainspec.UpgradeRules.adopting]] exists to keep:
    * a component's id is recorded from the component passed rather than from
    * anything its delta returned.
    *
    * **That is not the same claim as the height changing nothing.** The
    * emission steps down inside the region this upgrade governs -- the first
    * era ends at the era length, so the step lands on 5,000,001 rather than on
    * this height -- and from there every ommer's miner is paid under a different
    * rule; what does not move is any value a rule set holds. `org.fukuii.consensus.pow.EthashEngine` is where the step
    * is, and this network's own vectors certify it.
    */
  val gotham: UpgradeRules = dieHard.adopting(Ecip1017.component, Ecip1039.component)

  /** [[gotham]] with ECIP-1041 adopted.
    *
    * ==One proposal, and the sourcing is unusually short because of it==
    *
    * ECIP-1066 -- `ethereumclassic/ECIPs` @
    * `e36ef7f10166769aa3ac469aaf27ba5b0cacb198` (2026-07-05) -- lists
    * `ECIP-1041` alone under its `Incl EIPs` heading for this row and leaves
    * both remaining cells, `Specs` and `Blog`, empty. **ECIP-1041** is
    * [[Ecip1041]]'s, sourced there against three implementations that do not
    * derive from one another.
    *
    * ==Adopting it keeps ECIP-1010 rather than superseding it==
    *
    * The two documents concern one term and it is easy to read the later as
    * replacing the earlier. It does not: ECIP-1041's own implementation defers
    * to `PREVIOUS_FORMULA` below its height, and its specification names that
    * formula as ECIP-1010's window. So this rule set carries the removal AND
    * the window AND the graduated adjustment EIP-2 introduced, and the
    * distinction is reachable rather than notional -- seven of the sixteen
    * heights this network's own difficulty vectors state under this upgrade
    * answer differently with the window than without it.
    *
    * `besu-eth/besu-etc` @ `eb4248c997` reaches the same behavior by replacing
    * rather than composing, and the two readings agree at every height:
    * `ClassicProtocolSpecs.defuseDifficultyBombDefinition` builds on
    * `gothamDefinition` and swaps its difficulty calculator for one with no
    * exponential term, leaving the calculator carrying the window in force
    * below the fork. [[Ecip1041]] records that divergence, because it is about
    * where the branch is chosen rather than about what the branch answers.
    *
    * ==Nothing intervenes between this height and the one it builds on==
    *
    * `params/config_classic.go` at `4185df450` states no transition strictly
    * between `ECIP1017FBlock` 5,000,000 and `DisposalBlock` 5,900,000, and its
    * next one after that is 8,772,000. ECIP-1066's table at `e36ef7f1` orders
    * its rows the same way, with this upgrade between `Gotham | 5000000` and
    * `Atlantis | 8772000`. So the rules this composes over are [[gotham]]'s,
    * from two sources rather than by position in this file.
    */
  val defuse: UpgradeRules = gotham.adopting(Ecip1041.component)

  /** [[defuse]] with EIP-161, EIP-170, EIP-100, EIP-140, EIP-211, EIP-214,
    * EIP-658, EIP-198, EIP-196 and EIP-197 adopted.
    *
    * The order is the order the ten compose in. It is immaterial -- two settle
    * what the machine does with an account and how much code it will take, one
    * names what a block owes the mechanism that produced it, three add
    * operations at four bytes none of the others touches, one settles what a
    * receipt's first field holds, and three place natives at four addresses
    * none of them reaches -- and it is stated because two deltas touching one
    * field compose to whichever ran last.
    *
    * **That is a claim about KEYS, and not the stronger one about fields that
    * [[homestead]] and [[dieHard]] each make.** This composition cannot make
    * it: EIP-140, EIP-211 and EIP-214 all write the operation table, and
    * EIP-198, EIP-196 and EIP-197 all write the precompile set. Both are keyed
    * collections, each insert lands on a key no other delta here reaches --
    * `0x3d`, `0x3e`, `0xfa` and `0xfd` in the table, `0x05` through `0x08` in
    * the set -- and inserts at distinct keys commute where a field assignment
    * would not. The three natives read their prices out of the machine's
    * schedule, which no delta here writes, so what they read does not depend
    * on when they run.
    *
    * ==Ten, in two groups, and both sources state them as two==
    *
    * `ethereumclassic/core-geth` @ `4185df450` carries exactly ten transitions
    * at this height in `params/config_classic.go:56-67`, under two section
    * comments of its own: `// EIP158~` over `EIP161FBlock` and `EIP170FBlock`,
    * and `// Byzantium eq` over the remaining eight. ECIP-1054 --
    * `ethereumclassic/ECIPs` @ `f4ed3315e23427180b7437235667b6911255ab9d`,
    * Final, Meta -- draws the same division, opening on the two upgrades of the
    * other network these are taken from and tagging its first two abstract
    * items *Spurious Dragon*. Its specification section lists these ten and no
    * others.
    *
    * **The first group is what [[dieHard]] deferred**, arriving 5,772,000
    * blocks after the two proposals of that bundle this network did take.
    * `openethereum/openethereum` @ `v3.0.1` reads both groups at one height in
    * `ethcore/res/ethereum/classic.json`: `eip161abcTransition`,
    * `eip161dTransition` and `maxCodeSizeTransition` for the first group, five
    * further transitions for five of the second, and the four native prices the
    * remaining three settle -- every one of them at `0x85d9a0`.
    *
    * **What this composition contains is settled by ECIP-1054 and the
    * implementations, and not by a tabulation of them.** ECIP-1066 gives this
    * network's upgrades a row apiece and is cited above for this row's height
    * and label; its `Specs` cell points at ECIP-1054, which is the document that
    * enumerates the ten and the one to read for membership.
    *
    * **The division is worth stating because a tabulation and a specification
    * fail differently.** A specification is what a network resolved to adopt; a
    * table restates that in a second place, and a second place can lag or
    * disagree without the first having changed. So a row is a sound source for
    * where an upgrade sits and what it is called, and the wrong source for what
    * it carries -- whichever way any particular row happens to read.
    *
    * ==The second group is eight of nine==
    *
    * EIP-609 enumerates Byzantium's nine, and this composition takes all but
    * EIP-649. That proposal delays the difficulty bomb and cuts the block
    * reward; this network had removed the bomb outright at [[defuse]] and
    * replaced the emission with ECIP-1017's era ladder at [[gotham]], so
    * neither half of it has anything here to act on.
    *
    * **The exclusion is attested rather than merely unmentioned.**
    * `EIP649FBlock` is a field core-geth's own configuration type declares and
    * `params/config_classic.go` sets nowhere, and OpenEthereum carries no
    * `difficultyBombDelays` key in this network's specification while carrying
    * one in the specification it ships for the network this one parted from.
    *
    * **An aggregate fork setting could not have said that.** core-geth's other
    * configuration type reaches both proposals through one field --
    * `GetEthashEIP100BTransition` and `GetEthashEIP649Transition` in
    * `params/types/goethereum/goethereum_configurator.go` each return
    * `c.ByzantiumBlock` -- so taking the one this network wanted would take the
    * one it declined. Eight of nine is expressible at this grain and not that
    * one, which is why a component list rather than a fork name is what settles
    * the rules.
    *
    * ==What besu-etc corroborates, and the one thing it does not==
    *
    * `besu-eth/besu-etc` @ `eb4248c997` reaches the same membership by
    * replacement rather than by composition:
    * `ClassicProtocolSpecs.atlantisDefinition` installs the Byzantium operation
    * set at `:198`, the Byzantium precompile registry at `:203`,
    * `ClassicDifficultyCalculators.EIP100` at `:204`, the Byzantium receipt
    * factory at `:205-206`, a `MaxCodeSizeRule` at `:210` and
    * `clearEmptyAccounts(true)` at `:222`.
    *
    * **It is not a reading of what EIP-198 costs.** That same definition
    * installs `SpuriousDragonGasCalculator`, which does not override
    * `modExpGasCost`, and the interface default returns zero -- so modular
    * exponentiation is free in that client for this whole window. The divisor
    * of 20 in [[genesisPrices]] is OpenEthereum's and besu's own
    * `ByzantiumGasCalculator.GQUADDIVISOR`, never that definition's.
    */
  val atlantis: UpgradeRules =
    defuse.adopting(
      Eip161.component,
      Eip170.component,
      Eip100.component,
      Eip140.component,
      Eip211.component,
      Eip214.component,
      Eip658.component,
      Eip198.component,
      Eip196.component,
      Eip197.component
    )
