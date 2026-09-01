package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.{UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment, UpgradeRules}
import org.fukuii.chainspec.proposals.ecip.{Ecip1010, Ecip1017, Ecip1039, Ecip1041}
import org.fukuii.chainspec.proposals.eip.{
  Eip100,
  Eip1014,
  Eip1052,
  Eip1108,
  Eip1344,
  Eip140,
  Eip145,
  Eip150,
  Eip152,
  Eip155,
  Eip160,
  Eip161,
  Eip170,
  Eip1884,
  Eip196,
  Eip197,
  Eip198,
  Eip2,
  Eip2028,
  Eip211,
  Eip214,
  Eip2200,
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
    precompileBlake2fPerRound = BigInt(1),
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

  /** [[atlantis]] with EIP-145, EIP-1014 and EIP-1052 adopted.
    *
    * The order is the order the three compose in. It is immaterial -- and the
    * claim is [[atlantis]]'s about KEYS rather than the stronger one about
    * fields, because all three write the operation table: the inserts land on
    * `0x1b` through `0x1d`, on `0xf5` and on `0x3f`, and inserts at distinct
    * keys commute where a field assignment would not. The two priced entries
    * read the machine's schedule, which no delta here writes, so what they read
    * does not depend on when they run.
    *
    * ==Three proposals, and three lineages that do not derive from one another
    * state the same three==
    *
    * **ECIP-1056** -- `ethereumclassic/ECIPs` @
    * `7558f1ea4061f33bc21c8b93bbdd0c4796d61f17` (2020-01-12), Final, Meta -- is
    * the document by which this network adopted them, and its Specification
    * section enumerates *"EIP 145 (Bitwise shifting instructions)"*,
    * *"EIP 1014 (Skinny `CREATE2` opcode)"* and *"EIP 1052 (`EXTCODEHASH`
    * opcode)"* and nothing else.
    *
    * `ethereumclassic/core-geth` @ `4185df450` states them as three per-proposal
    * transitions rather than as a fork name -- `EIP145FBlock`, `EIP1014FBlock`
    * and `EIP1052FBlock` in `params/config_classic.go:70-72`.
    * `openethereum/openethereum` @ `v3.0.1` states the same three in a different
    * shape, as `eip145Transition`, `eip1014Transition` and `eip1052Transition`
    * in `ethcore/res/ethereum/classic.json`; a walk over every key of that
    * document finds those three holding this upgrade's height and no fourth,
    * against nine keys holding [[atlantis]]'s.
    *
    * `besu-eth/besu-etc` @ `eb4248c997` reaches the same membership by
    * replacement: `ClassicProtocolSpecs.aghartaDefinition` builds on
    * `atlantisDefinition` and swaps in `MainnetEVMs::constantinople`, whose
    * `registerConstantinopleOperations` puts exactly five operations over
    * Byzantium's -- `Create2Operation`, `SarOperation`, `ShlOperation`,
    * `ShrOperation` and `ExtCodeHashOperation`. That is the first document's
    * three plus the second's one plus the third's one, and nothing else.
    *
    * ==What this network declines here is what makes the upgrade three rather
    * than five==
    *
    * EIP-1013 bundles Constantinople's five and this composition takes three.
    * Both of the others are proposals this build carries and could adopt, so
    * their absence is a choice this composition makes rather than one it
    * inherits from a vocabulary that lacks them.
    *
    * **EIP-1234** has nothing here to act on, for the reason [[atlantis]] gives
    * for declining EIP-649: it delays the difficulty bomb and cuts the block
    * reward, and this network removed the bomb outright at [[defuse]] and
    * replaced the emission with an era ladder at [[gotham]]. The exclusion is
    * attested rather than merely unmentioned -- `EIP1234FBlock` is declared
    * `json:"-"` in core-geth's own configuration type at `4185df450`
    * (`params/types/coregeth/chain_config.go:131-132`) and inferred from a bomb
    * delay and a reward schedule that `ClassicChainConfig` sets neither of, so
    * the inference yields nothing at any height.
    *
    * **EIP-1283** is what would make this upgrade Petersburg under another name
    * rather than Constantinople minus one proposal, and it is the more
    * interesting of the two:
    *
    * ==This network reaches legacy storage metering by never leaving it==
    *
    * `params/config_classic.go:73-74` at `4185df450` carries
    * `EIP1283FBlock` and `PetersburgBlock` at this height **commented out**, and
    * the two spellings name one rule set. `PetersburgBlock` is not a fork field
    * in that client: `chain_config_configurator.go:331-333` defines
    * `GetEIP1283DisableTransition` as returning it and nothing else reads it, so
    * uncommenting it would disable something this network never enabled.
    * EIP-1716 -- `ethereum/EIPs` @ `dbfa6bee`, Final -- licenses the second
    * spelling directly: *"If `Petersburg` and `Constantinople` are applied at
    * the same block, `Petersburg` takes precedence: with the net effect of
    * EIP-1283 being disabled"*.
    *
    * `openethereum/openethereum` @ `v3.0.1` proves the identity by taking the
    * opposite route to the same behavior. Its `classic.json` carries
    * `eip1283Transition: 0x0` **and** `eip1283DisableTransition: 0x0` -- enabled
    * at genesis and disabled at genesis -- with
    * `ethcore/types/src/engines/params.rs:177-180` computing the flag as
    * enabled-and-not-disabled, or re-enabled. So one implementation never
    * enables it and another enables and disables it in the same block, and both
    * charge the legacy metering here.
    *
    * **Same value, different journal**, which is what
    * [[org.fukuii.chainspec.UpgradeRules.components]] exists to keep. The other
    * network reaches `org.fukuii.evm.StorageMetering.Legacy` at its own
    * counterpart upgrade by a round trip -- adopting EIP-1283 and then EIP-1716
    * -- and its record says so. This one never adopted either, and its record
    * says that instead.
    *
    * ==So these rules are NOT the other network's, though the machine's delta is
    * the same three==
    *
    * The two partings are independent of one another. **EIP-1234 is in the other
    * network's composition and in no rule set here at any height**, so the
    * consensus facets differ in what a block is paid and in the bomb terms. And
    * the bases differ: that composition descends from the other network's
    * Byzantium, this one from [[atlantis]], which carries ECIP-1010, ECIP-1017,
    * ECIP-1039 and ECIP-1041 and takes EIP-161 and EIP-170 at the same block as
    * the Byzantium set rather than millions of blocks earlier.
    *
    * `org.fukuii.chainspec.networks.SharedHistorySpec` asserts both halves at
    * once -- the machine, settlement and admission facets equal, and the
    * consensus facet not -- because either half alone misstates the relation.
    *
    * ==What besu-etc corroborates here, and the one thing it must not be cited
    * for==
    *
    * It is a reading of the operation set, above, and of the gas calculator:
    * `PetersburgGasCalculator extends ConstantinopleGasCalculator` and is
    * documented *"Rollback EIP-1283"*, which is that client's way of writing
    * Constantinople minus that one proposal.
    *
    * **It is not a reading of this window's precompile set.**
    * `aghartaDefinition` installs
    * `MainnetPrecompiledContractRegistries::istanbul` where
    * `atlantisDefinition` had `byzantium`, which reprices the alt_bn128 natives
    * and places one at a ninth address -- EIP-1108 and EIP-152, both of them
    * proposals `params/config_classic.go:78-79` puts at 10,500,839, which is
    * 927,839 blocks above this height. Its own `phoenixDefinition` installs that
    * same registry again. So the natives here are [[atlantis]]'s, unchanged, and
    * this composition adopts nothing that touches them.
    */
  val agharta: UpgradeRules = atlantis.adopting(Eip145.component, Eip1014.component, Eip1052.component)

  /** [[agharta]] with EIP-152, EIP-1108, EIP-1344, EIP-1884, EIP-2028 and
    * EIP-2200 adopted.
    *
    * The order is the order the six compose in. It is immaterial, and the
    * claim is stronger in one place than [[atlantis]]'s and the same in two
    * others.
    *
    * **On the machine's schedule it is the claim about FIELDS.** EIP-1108
    * moves four native prices, EIP-1884 three operation prices, EIP-2028 one
    * transaction charge, and EIP-2200 one metering figure with the two refunds
    * defined as differences from it; no two of the six name one field.
    *
    * **On the operation table and the precompile set it is [[atlantis]]'s
    * weaker claim about KEYS**, because two deltas write each collection:
    * `0x46` against `0x31`, `0x3f`, `0x47` and `0x54` in the table, and `0x09`
    * against `0x06` through `0x08` in the set. Inserts at distinct keys commute
    * where a field assignment would not.
    *
    * **The third clause is the one [[atlantis]] and [[agharta]] each state more
    * simply than this composition can.** Both of those say that the priced
    * entries they build read a schedule no delta of theirs writes, and four of
    * the six here DO write it. Three deltas build an entry from a price they do
    * not themselves set -- the per-round figure a Blake2 invocation is charged,
    * the tier `CHAINID` is priced at, and the tier `SELFBALANCE` is priced at.
    * What holds is narrower and is still enough: none of those three fields is
    * one any of the six writes, so what they read does not depend on when they
    * run. The other two build their entries out of a schedule they have just
    * written themselves, which fixes the order inside one delta rather than
    * between two.
    *
    * ==This is the first upgrade at which this network takes an upstream set
    * WHOLE==
    *
    * [[atlantis]] takes eight of EIP-609's nine and [[agharta]] three of
    * EIP-1013's five, each declining a proposal with nothing here to act on.
    * This composition declines nothing: EIP-1679 -- `ethereum/EIPs` @
    * `dbfa6bee8329650969b95080f23f7059c015c2ba`, Final -- lists exactly these
    * six under *Included EIPs*, and every one of them is adopted here.
    *
    * **That does not make these rules the other network's.** The bases differ
    * by four proposals in each direction, and adding one set to both leaves
    * that difference exactly where it was.
    * `org.fukuii.chainspec.networks.SharedHistorySpec` asserts the machine,
    * settlement and admission facets equal, the consensus facet not, and the
    * two records differing by the same four proposals in each direction that
    * they differed by one upgrade below.
    *
    * ==Which document settles membership, and the three at this height that do
    * not==
    *
    * **ECIP-1088** -- `ethereumclassic/ECIPs` @
    * `f1bb761a80bcaae10c7b5e0c39e4a62062b1c023`, Final, Meta -- is the document
    * by which this network adopted them. Its Specification section enumerates
    * these six and nothing else, and the six-bullet list in its own Abstract
    * agrees with it.
    *
    * **Reading `status:` is what makes that a membership statement rather than
    * a search result.** Four documents in that registry propose a hard fork at
    * this network's Phoenix height and two of them are titled *Phoenix*.
    * ECIP-1089 -- @ `5f59493da7b2a8269994c68e726548677618a323`, **Withdrawn** --
    * shares this document's title stem, its creation date, its discussion issue
    * and all three of its block heights, and proposes SEVEN members: it drops
    * EIP-1884 and EIP-2200 and adds EIP-1283, EIP-1706 and ECIP-1080.
    * ECIP-1061 -- @ `cf2e440dafc686820c16f8198d2896634272ecad`, **Rejected** --
    * proposes five, arguing in its own text that EIP-1884 be deferred.
    * ECIP-1078 -- @ `98ecc1a33c2995e5a5dad591105aaf6302a41ca7`, **Rejected** --
    * proposes a third set. A membership matched on the name and the height
    * would reach one of those three, and only the fourth is Final.
    *
    * ==Three implementations state the same six, and a fourth is not a fourth
    * reading==
    *
    * `ethereumclassic/core-geth` @ `4185df450` states them as six per-proposal
    * transitions rather than as a fork name -- `EIP152FBlock`, `EIP1108FBlock`,
    * `EIP1344FBlock`, `EIP1884FBlock`, `EIP2028FBlock` and `EIP2200FBlock` in
    * `params/config_classic.go:78-83`, under section comments naming both the
    * upstream upgrade and ECIP-1088. `besu-eth/besu-etc` @ `eb4248c997` reaches
    * the same six by replacement: `ClassicProtocolSpecs.phoenixDefinition`
    * builds on `aghartaDefinition`, swaps in `IstanbulGasCalculator` and
    * `MainnetEVMs.istanbul`, and re-installs the Istanbul precompile registry
    * that base already carried -- which is the reading the section below is
    * about.
    *
    * **`openethereum/openethereum` @ `v3.0.1` states the same six and is NOT a
    * third lineage here.** The commit that configured this upgrade in that tree
    * shares an author with ECIP-1088, so it restates the specification rather
    * than reading it independently -- which is a different relation from the
    * one the preceding upgrades cite it for, and the reason it is not counted
    * among the readings above. **Its predecessor tree is worse than not
    * independent: `openethereum/parity-ethereum` @ `55c90d401` carries this
    * network's configuration from before ECIP-1088 and holds
    * `eip1884Transition` at no height at all**, so a membership read there is
    * missing the proposal this upgrade exists to have taken.
    *
    * That client also spells EIP-2200 as `eip1283ReenableTransition` plus
    * `eip1706Transition` and carries no key named for the document at any
    * height, which is its universal spelling rather than a choice made for this
    * network -- `ethcore/res/ethereum/foundation.json` decomposes the same
    * proposal the same way at `0x8a61c8` for the network this one parted from.
    * Neither key is a seventh proposal.
    *
    * ==Net-metered storage arrives here having never been in force, which is
    * not how the other network reaches it==
    *
    * [[agharta]] records that this network reaches
    * `org.fukuii.evm.StorageMetering.Legacy` by never leaving it -- it adopted
    * neither EIP-1283 nor EIP-1716, where the other network adopted both.
    * [[Eip2200]] writes `org.fukuii.evm.StorageMetering.NetWithSentry`
    * absolutely rather than as an amendment to EIP-1283's state, so this
    * composition moves from `Legacy` to `NetWithSentry` in one step where the
    * other network's journal is an adoption, a withdrawal and then this.
    *
    * **Same value, different journal, one upgrade further on** -- and this time
    * the journals converge on the same rule rather than on the same absence.
    *
    * The three figures the document lists as *"not changed"* --
    * `SSTORE_SET_GAS`, `SSTORE_RESET_GAS` and `SSTORE_CLEARS_SCHEDULE` -- are
    * supplied by [[genesisPrices]] at 20000, 5000 and 15000 and are written by
    * no component this build carries. That is a property of the vocabulary as
    * it stands rather than a guarantee, so `UpgradesSpec` asserts it by running
    * this composition rather than by reading this paragraph.
    *
    * ==Two of the six move a figure both documents call `SLOAD_GAS`, and this
    * network split two of its own test networks over exactly that==
    *
    * EIP-1884 takes `org.fukuii.evm.GasSchedule.storageLoad` from 200 to 800,
    * which is what the `SLOAD` OPERATION costs. EIP-2200 takes `netStorageNoop`
    * and `netStorageDirty` from 200 to 800, which is what the same quantity is
    * worth INSIDE the storage-write calculation. One constant in the two
    * documents, three fields in this schedule, and neither document's fields
    * are the other's.
    *
    * **ECIP-1086 -- @ `a852d25db550518e9199a90e77359f0162f60294`, Rejected -- is
    * this network's own record of what taking one and not the other cost it.**
    * Verbatim: *"Both [EIP-1884] and [EIP-2200] propose the `SLOAD_GAS` to be
    * increased from `200` to `800` but not all clients correctly implemented
    * this change for both EIPs leaving networks configured with a pick-and-mix
    * EIP configuration ... with an incompatible configuration as compared to
    * clients who correctly implemented the specifications."* That document
    * proposes pinning the figure at 200 on the test networks and says of
    * itself *"This ECIP shall not be considered on mainnet."*
    *
    * **So a five-member composition dropping EIP-1884 is not a smaller Phoenix;
    * it is the configuration this network's own registry records as broken**,
    * and it is what ECIP-1061 proposed. The three-field split is what makes
    * that configuration expressible and therefore refutable -- `UpgradesSpec`
    * builds it and asserts that this composition is not it. A schedule sharing
    * one field between the two documents could not tell them apart at all.
    *
    * ==What besu-etc corroborates here, and the one thing it must not be cited
    * for==
    *
    * It is a reading of all six proposals' CONTENT and of this upgrade's
    * membership. **It is not a reading of EIP-152's or EIP-1108's ACTIVATION
    * HEIGHT.** `ClassicProtocolSpecs.aghartaDefinition` installs
    * `MainnetPrecompiledContractRegistries::istanbul`, which is exactly those
    * two proposals, and has done so since a commit predating ECIP-1088 --
    * [[agharta]] records the same finding from the other side. So in that
    * client BLAKE2F is callable and the alt_bn128 natives carry this document's
    * prices 927,839 blocks below where the specification and the other two
    * implementations put them, and both are state-root-affecting if exercised.
    *
    * ==Every facet but the machine's is untouched==
    *
    * All six are `org.fukuii.chainspec.Component.evm` deltas, which cannot
    * reach the consensus, settlement or admission facets. Two implementations
    * agree from their own shapes: the six transitions above are every
    * transition `params/config_classic.go` at `4185df450` carries between
    * [[agharta]]'s height and 11,380,000, and besu-etc's `phoenixDefinition`
    * sets a gas calculator, an EVM builder and a precompile registry and no
    * block-header validator, difficulty calculator, block processor or receipt
    * factory. A third reading, from another module and written before this
    * composition existed, agrees: this network's difficulty corpus dispatches
    * this upgrade's label to [[atlantis]]'s consensus rules unchanged.
    */
  val phoenix: UpgradeRules =
    agharta.adopting(
      Eip152.component,
      Eip1108.component,
      Eip1344.component,
      Eip1884.component,
      Eip2028.component,
      Eip2200.component
    )
