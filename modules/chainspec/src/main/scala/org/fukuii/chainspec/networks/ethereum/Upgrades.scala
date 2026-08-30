package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.{UInt256, UInt64}
import org.fukuii.chainspec.{ConsensusRules, DifficultyAdjustment, UpgradeRules}
import org.fukuii.chainspec.proposals.eip.{
  Eip100,
  Eip1014,
  Eip1052,
  Eip1234,
  Eip1283,
  Eip140,
  Eip145,
  Eip1716,
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
  Eip649,
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

/** The configuration Ethereum launched with, and the rule sets it reached from
  * it by adopting proposals.
  *
  * ==Why these are HERE and not in the machine==
  *
  * Every value below was a privileged constant inside `modules/evm` until this
  * point -- the machine held one network's prices, one network's choice of
  * precompiles, and three of that network's rule sets, so a second network
  * could only be expressed as a delta from the first one this project
  * integrated. That is the shape a client acquires when it grows a second
  * network rather than being built for several, and both surveyed clients that
  * did grow one show it: `besu-eth/besu` @ `c2addd9424` names its equivalents
  * `MainnetEVMs` and `MainnetProtocolSpecs`, and `besu-eth/besu-etc` @
  * `eb4248c997` has to build Ethereum Classic's Tangerine Whistle by inheriting
  * from Ethereum mainnet's *Homestead* definition.
  *
  * The machine now holds the shape and the components; the numbers, the
  * membership and the compositions are a network's and live here.
  *
  * ==Another network reached rule sets equal to these, and does not refer to
  * them==
  *
  * Ethereum Classic shares one history with this network to the DAO fork and
  * adopted EIP-2, EIP-7 and EIP-150 unaltered, so the RULES at each of those
  * points are the same value on both. It reaches them by composing from the
  * same proposals rather than by naming these, which is what makes *"the two
  * agree"* a claim a test can refute: values built from one value are equal
  * however either was authored, so an assertion over them would report the
  * sharing rather than the agreement. [[ethereumclassic.Upgrades]] holds the
  * other composition and `SharedHistorySpec` holds the assertion.
  *
  * What differs between the two is when each switched a rule set on and what
  * each calls it, and neither of those is here: an activation belongs to a
  * schedule and a label belongs to `UpgradeId`.
  *
  * **The fork names are Ethereum's own labels and are correct in this file for
  * that reason.** They were deliberately NOT used while these values sat in the
  * machine, because a value shared by every network cannot carry one network's
  * name for it. Here that objection does not apply.
  *
  * ==No activation and no schedule==
  *
  * A rule set carries neither. Which block each of these starts at is a
  * separate fact, external to this file and to this module's types, and is
  * stated where a schedule is authored.
  */
object Upgrades:

  /** The prices Ethereum launched with, and the floor its later schedules are a
    * change to.
    *
    * Read from `ethereum/execution-specs` @ `ccaaaba58`,
    * `forks/frontier/vm/gas.py` and the intrinsic costs beside it. Every one of
    * these is a number a network sets for itself; none of them is the machine's.
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

  /** The four natives Ethereum placed at genesis, priced by [[genesisPrices]].
    *
    * **Built up from [[PrecompileSet.Empty]] rather than cut down**, which is
    * what makes this a network's choice rather than a default with exceptions.
    * A network launching at a later fork places more of them, and one may place
    * its own; in `ethereum-optimism/op-geth` @ `86be6726f` and `ronin/ronin` @
    * `84f1c2260` every chain-specific token under `core/vm` is in the
    * precompile path, and neither client's jump table carries one.
    *
    * The addresses are the ecosystem's and stay with the implementations in the
    * machine. Which of them a chain runs is this.
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

  /** What this network pays the producer of a block, from its first block until
    * a proposal changes it.
    *
    * Five ether, expressed in wei because that is the unit a balance is held
    * in. `ethereum/execution-specs` @ `ccaaaba58` states it as
    * `BLOCK_REWARD = U256(5 * 10**18)` and `ethereum/go-ethereum-pow` @
    * `v1.10.26` as `FrontierBlockReward = big.NewInt(5e+18)`.
    *
    * **This is the amount alone and not the emission.** What a block's producer
    * actually receives is this plus a share for each ommer the block included,
    * and what an ommer's producer receives is a share scaled by its age; both
    * are arithmetic over this number rather than further numbers, which is why
    * a rule set carries one figure and the mechanism carries the formulas.
    */
  val launchReward: UInt256 =
    UInt256
      .fromBigInt(BigInt(5) * BigInt(10).pow(18))
      .getOrElse(throw new IllegalStateException("five ether does not fit a 256-bit quantity"))

  /** The rules Ethereum launched with: the original instruction set at this
    * network's prices, its four precompiles, and no proposal adopted.
    *
    * **Adopting nothing is the network's choice being recorded, not an absence
    * of one.** Every later network's configuration is this same root with a
    * list of proposals over it, and the list being empty is what makes this
    * genesis rather than a fork.
    *
    * The two settlement rules are both the earlier of their pair, and both are
    * read from `ethereum/execution-specs` @ `ccaaaba58` rather than inferred
    * from the proposals being unadopted: `forks/frontier/fork.py` calls nothing
    * that destroys a touched empty account, and `forks/frontier/blocks.py`
    * gives a receipt's first field as `post_state: Root`.
    *
    * The one format admitted is the shape that predates the envelope. The same
    * source states it: `forks/frontier/transactions.py` declares one
    * `Transaction`, whose fields are the legacy nine, and the fork has no
    * envelope to put a tag in.
    *
    * ==The emission is this network's, and it is a value a fork resolves==
    *
    * Five ether, from two sources that do not derive from one another.
    * `ethereum/execution-specs` @ `ccaaaba58` declares
    * `BLOCK_REWARD = U256(5 * 10**18)` at `forks/frontier/fork.py:58`, and
    * `ethereum/go-ethereum-pow` @ `v1.10.26` declares
    * `FrontierBlockReward = big.NewInt(5e+18)` at
    * `consensus/ethash/consensus.go:42` and selects it in `accumulateRewards`
    * for every height below the first fork that changes it.
    *
    * **That the amount is a fork's answer rather than a mechanism's is what
    * puts it here.** One mechanism runs every height the specification models
    * before the merge, and the amount changes twice under it -- to three ether
    * at Byzantium and to two at Constantinople, both in the same
    * `BLOCK_REWARD` slot of the corresponding fork module. Neither of those
    * heights is in this build, so all three rule sets here pay the launch
    * amount.
    *
    * A reward of zero credits the beneficiary, which is unobservable at five
    * ether and is stated because it is the value the field states. besu writes
    * it as `.skipZeroBlockRewards(false)` at its Frontier definition and
    * flips it to `true` at Spurious Dragon, the fork that begins deleting
    * touched empty accounts; the specification and `go-ethereum-pow` both
    * credit unconditionally at this height, which is the same answer written
    * as the absence of a check.
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
    * The order is the order the two compose in. It is immaterial -- the four
    * deltas between them touch disjoint fields -- and it is stated because two
    * deltas touching one field compose to whichever ran last.
    */
  val homestead: UpgradeRules = frontier.adopting(Eip7.component, Eip2.component)

  /** [[homestead]] with EIP-150 adopted.
    *
    * **Each family shipped this proposal on its own**, which is worth stating
    * because the opposite is the natural guess and it is wrong. `EIP-608` names
    * one included proposal; `ECIP-1015` proposes one action; and
    * `ethereumclassic/core-geth` at `4185df450` activates `EIP150Block` at
    * 2,500,000 with nothing beside it. The regrouping is one fork later --
    * Ethereum takes `EIP155Block` and `EIP158Block` together at 2,675,000,
    * Ethereum Classic takes `EIP155Block` with `EIP160FBlock` at 3,000,000 and
    * defers `EIP161FBlock` and `EIP170FBlock` to 8,772,000 -- which is what
    * makes a fork name a family's label rather than a description of a rule set.
    */
  val tangerineWhistle: UpgradeRules = homestead.adopting(Eip150.component)

  /** [[tangerineWhistle]] with EIP-155, EIP-160, EIP-161 and EIP-170 adopted.
    *
    * The order is the order the four compose in. It is immaterial -- they write
    * a member of the admission facet, a price in the machine's schedule, three
    * members of the machine's rules with one of the settlement's, and a bound in
    * the machine's rules, and no two of them name one field -- and it is stated
    * because two deltas touching one field compose to whichever ran last.
    *
    * ==This composition is the whole of what this network calls Spurious
    * Dragon==
    *
    * `ethereum/EIPs` @ `9e393a79d`, EIP-607 *Hardfork Meta: Spurious Dragon*
    * (Final) lists exactly these four. That is a property of this upgrade rather
    * than a property a composition has to have: a component list states which
    * proposals produced a rule set and never claims to be an upgrade, which is
    * what the standing [[org.fukuii.chainspec.networks.ethereum.Mainnet]]
    * already relies on where EIP-606 includes a proposal that is devp2p rather
    * than a rule.
    *
    * ==Four proposals, four facets, and the grouping is this network's alone==
    *
    * Ethereum took these together. `ethereumclassic/core-geth` @ `4185df450`
    * puts `EIP155Block` and `EIP160FBlock` at 3,000,000 on Ethereum Classic and
    * defers `EIP161FBlock` and `EIP170FBlock` to 8,772,000, so the four
    * proposals EIP-607 bundles reach that network as two groups 5,772,000 blocks
    * apart. A rule set composed from proposals expresses that by adopting a
    * different list; one derived from a fork's name could not express it at all.
    */
  val spuriousDragon: UpgradeRules =
    tangerineWhistle.adopting(Eip155.component, Eip160.component, Eip161.component, Eip170.component)

  /** [[spuriousDragon]] with EIP-100, EIP-649, EIP-140, EIP-211, EIP-214,
    * EIP-658, EIP-198, EIP-196 and EIP-197 adopted.
    *
    * The order is the order the nine compose in. It is immaterial -- two name
    * what a block owes the mechanism that produced it, three add operations at
    * four bytes none of the others touches, one settles what a receipt's first
    * field holds, and three place natives at four addresses none of them
    * reaches -- and it is stated because two deltas touching one field compose
    * to whichever ran last.
    *
    * ==Three facets, and the pairing within each is the document's rather than
    * this network's==
    *
    * EIP-100 and EIP-649 write the consensus facet;
    * `org.fukuii.chainspec.proposals.eip.Eip100Spec` and
    * `org.fukuii.chainspec.proposals.eip.Eip649Spec` each assert the other
    * facets survive as the same values rather than as equal copies.
    * EIP-140, EIP-211 and EIP-214 write the machine. The first two are adopted
    * together because each document's own specification asserts something about
    * the other's work: EIP-140 places its payload in a buffer EIP-211 defines,
    * and EIP-211 names EIP-140 by number as the source of the failure data it
    * carries. The third is independent of both and is adopted here because this
    * network took all three at one height.
    *
    * EIP-658 writes the settlement facet, and it is the second document here
    * that names EIP-140 as a dependency -- its header is `requires: 140`,
    * because a status is what tells a failure that kept its gas from a success.
    * So the two arrive together on this network and would have to be reasoned
    * about separately on one that took them apart;
    * `org.fukuii.chainspec.proposals.eip.Eip658` states what such a network
    * would get.
    *
    * EIP-198, EIP-196 and EIP-197 write the machine too, and they are the three
    * of the nine that reach the precompile set rather than the operation table.
    * None of them depends on any of the others and none of the others depends
    * on them: a native answers at an address, so they share no byte with the
    * three that add operations and nothing about them is reachable from a
    * table.
    *
    * The last two are read together -- each names the other by number, and the
    * second's stated purpose is combining them -- and adopted apart, because
    * neither declares the other a dependency. `Eip196` places two entries from
    * one document, which is that document's own shape rather than a grouping
    * this network chose.
    *
    * Which proposals of this network's upgrade are carried here is stated on
    * the schedule entry that resolves them, because that is where a reader asks
    * what a node validates at a height.
    */
  val byzantium: UpgradeRules =
    spuriousDragon.adopting(
      Eip100.component,
      Eip649.component,
      Eip140.component,
      Eip211.component,
      Eip214.component,
      Eip658.component,
      Eip198.component,
      Eip196.component,
      Eip197.component
    )

  /** The rules EIP-1013 specifies, which this network never ran.
    *
    * ==All five of the document's Included EIPs, EIP-1283 among them==
    *
    * EIP-1013 lists EIP-145, EIP-1014, EIP-1052, EIP-1234 and EIP-1283
    * (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1013.md`, Final), and this
    * composition adopts every one.
    *
    * ==NO HEIGHT OF THIS NETWORK RESOLVES TO THIS VALUE, and that is not a
    * defect==
    *
    * `Mainnet` schedules this and [[petersburg]] at the SAME block, and a
    * schedule answers with the last entry that has activated -- so from
    * 7,280,000 onward this network runs [[petersburg]], and below it
    * [[byzantium]]. This value is what the fork was SPECIFIED to be.
    *
    * **It is built rather than skipped for three reasons, none of them
    * academic.** Other networks ran it: Ropsten for 709,394 blocks, Kovan for
    * 1,055,201, Rinkeby for 660,571, and Gnosis turned it on, off and on again.
    * The published legacy conformance corpus states expectations under a
    * `Constantinople` label that are these rules and not [[petersburg]]'s. And
    * a schedule that could not express an adoption followed by a withdrawal
    * would have to model the pair as one fork, which is what
    * `ethereum/execution-specs` does and what two production clients decline to
    * do.
    *
    * The alternative -- four proposals and no withdrawal -- was surveyed and
    * declined: `ethereum/go-ethereum` @ `e9e35a42f8` and `besu-eth/besu` @
    * `fdf1247c6d` each shipped this fork first and added the removal months
    * later, so the two-value shape is what a client that lived through the
    * sequence carries.
    */
  val constantinople: UpgradeRules =
    byzantium.adopting(
      Eip145.component,
      Eip1014.component,
      Eip1052.component,
      Eip1234.component,
      Eip1283.component
    )

  /** The rules this network actually runs from 7,280,000: Constantinople with
    * EIP-1283 taken back out.
    *
    * ==Reached by withdrawing rather than by composing four proposals==
    *
    * The two routes produce the same MACHINE -- `Eip1716Spec` asserts that
    * withdrawing leaves the rules exactly where they were before the
    * adoption -- and a different RECORD. [[UpgradeRules.components]] is an
    * ordered journal of what was applied, so this one states that EIP-1283 was
    * adopted and then removed, which is what happened and what a four-proposal
    * composition could not say.
    *
    * ==The name==
    *
    * `Petersburg` is EIP-1716's codename and is what five clients call the
    * field. **It is not what any conformance corpus calls the fork**: the
    * generated tier publishes `for_constantinoplefix` and no
    * `for_constantinople` at all, and the legacy tier carries `Constantinople`
    * and `ConstantinopleFix` and uses `Petersburg` zero times. Anything
    * matching a corpus label wants `ConstantinopleFix`.
    */
  val petersburg: UpgradeRules = constantinople.adopting(Eip1716.component)
