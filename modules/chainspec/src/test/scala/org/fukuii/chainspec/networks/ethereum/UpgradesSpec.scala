package org.fukuii.chainspec.networks.ethereum

import org.fukuii.bytes.{UInt256, UInt64}
import org.fukuii.chainspec.{DifficultyAdjustment, ProposalId}
import org.fukuii.evm.{
  Cost,
  NewAccountCharge,
  Opcode,
  OpcodeTable,
  Operation,
  Precompile,
  PrecompileSet,
  StateAccessMetering,
  StorageMetering
}
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** Properties every rule set this network composed has to hold. */
class UpgradesSpec extends AnyFlatSpec:

  /** Every rule set this network composes, in the order it reaches them.
    *
    * **All of them, which is what makes a property asserted over this one a
    * property of the network rather than of its early history.** A vector that
    * stopped partway would exempt the compositions above it silently: the case
    * would pass, name no omission, and go on passing as further upgrades were
    * added below its own ceiling.
    */
  private val composed =
    Vector(
      Upgrades.frontier,
      Upgrades.homestead,
      Upgrades.tangerineWhistle,
      Upgrades.spuriousDragon,
      Upgrades.byzantium,
      Upgrades.constantinople,
      Upgrades.petersburg,
      Upgrades.istanbul,
      Upgrades.muirGlacier,
      Upgrades.berlin
    )

  /** The compositions below the one that adopts EIP-658.
    *
    * Named rather than derived by removing [[Upgrades.byzantium]] from
    * [[composed]]. Those two were the same vector only while [[composed]]
    * stopped at that upgrade, so a filter expressing "below" would have quietly
    * begun including everything above it the moment a later composition was
    * added -- and the case that reads this asserts a property that is false
    * above.
    */
  private val belowTheStatusByte =
    Vector(Upgrades.frontier, Upgrades.homestead, Upgrades.tangerineWhistle, Upgrades.spuriousDragon)

  /** Every rule set this network composed below the one that reduces the
    * amount, which is what the launch figure is asserted over.
    */
  private val payingTheLaunchAmount =
    Vector(Upgrades.frontier, Upgrades.homestead, Upgrades.tangerineWhistle, Upgrades.spuriousDragon)

  private def ether(count: Int): UInt256 =
    UInt256.fromBigInt(BigInt(count) * BigInt(10).pow(18)).getOrElse(fail("a whole number of ether is not a word"))

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  /** The six proposals this network's Istanbul is composed from, in the order
    * [[Upgrades.istanbul]] adopts them.
    *
    * Held here rather than written into each case so that the membership and
    * the order are stated once. `ethereum/EIPs` @ `dbfa6bee`, EIP-1679
    * *Hardfork Meta: Istanbul* (Final) lists exactly these under *Included
    * EIPs*; its `requires:` frontmatter lists a seventh, EIP-1716, which is
    * Petersburg's own meta proposal and is adopted at the upgrade below.
    */
  private val istanbulProposals =
    Vector(
      ProposalId.Eip(152),
      ProposalId.Eip(1108),
      ProposalId.Eip(1344),
      ProposalId.Eip(1884),
      ProposalId.Eip(2028),
      ProposalId.Eip(2200)
    )

  /** The four proposals this network's Berlin is composed from, in the order
    * [[Upgrades.berlin]] adopts them.
    *
    * Held here rather than written into each case so that the membership and the
    * order are stated once. **No document lists them**: the two metas naming this
    * upgrade delegate to a file in `ethereum/execution-specs` that reads only at
    * the commit the Final one cites, so the membership was established from five
    * client and specification readings rather than from a section a reader can
    * open at that repository's head.
    */
  private val berlinProposals =
    Vector(ProposalId.Eip(2565), ProposalId.Eip(2718), ProposalId.Eip(2929), ProposalId.Eip(2930))

  "every rule set this network composes" should "leave SELFDESTRUCT working out its own price" in {
    // The mirror of the two-homes hazard, in the direction the schedule's own
    // taxonomy does not cover. Nothing reads this entry's cost, so a proposal
    // settling one -- `table.adding(Operation(Opcode.SelfDestruct,
    // Cost.Fixed(n)))` -- would compile, pass, and be charged the schedule's
    // figure instead of `n`, silently. The guard that catches the opposite
    // mismatch is the interpreter reporting an operation it cannot price; there
    // is none in this direction, so it is asserted across every composition
    // rather than at the first alone.
    val settled = composed.filter(rules => settledCost(rules.evm.table, Opcode.SelfDestruct).isDefined)
    assert(settled.isEmpty, "a fork settled a price for an operation that works out its own")
  }

  it should "record exactly the proposals it adopted, in the order it adopted them" in
    // The component list is what determines the rules, so a composition whose
    // list disagrees with what it applied cannot be compared against another
    // network's meaningfully.
    assert(
      Upgrades.frontier.components == Vector.empty &&
        Upgrades.homestead.components == Vector(ProposalId.Eip(7), ProposalId.Eip(2)) &&
        Upgrades.tangerineWhistle.components == Vector(ProposalId.Eip(7), ProposalId.Eip(2), ProposalId.Eip(150)) &&
        Upgrades.spuriousDragon.components ==
        Vector(
          ProposalId.Eip(7),
          ProposalId.Eip(2),
          ProposalId.Eip(150),
          ProposalId.Eip(155),
          ProposalId.Eip(160),
          ProposalId.Eip(161),
          ProposalId.Eip(170)
        ) &&
        Upgrades.byzantium.components ==
        Vector(
          ProposalId.Eip(7),
          ProposalId.Eip(2),
          ProposalId.Eip(150),
          ProposalId.Eip(155),
          ProposalId.Eip(160),
          ProposalId.Eip(161),
          ProposalId.Eip(170),
          ProposalId.Eip(100),
          ProposalId.Eip(649),
          ProposalId.Eip(140),
          ProposalId.Eip(211),
          ProposalId.Eip(214),
          ProposalId.Eip(658),
          ProposalId.Eip(198),
          ProposalId.Eip(196),
          ProposalId.Eip(197)
        ),
      "a composition's recorded components are not the ones it adopted"
    )

  it should "carry every clause of EIP-161 at the upgrade that adopted it" in
    // Asserted at the NETWORK rather than at the component, because the
    // settlement member here sat on the record with no production reader and no
    // network setting it, and a component nothing adopts leaves it exactly
    // there. All four in one case on purpose: the document's clause (c) is
    // satisfied by clause (d) rather than by a member of its own, so a rule set
    // carrying the machine's three and not the settlement's would claim a clause
    // it does not have.
    assert(
      Upgrades.spuriousDragon.evm.createdAccountNonce == UInt64.fromBits(1L) &&
        Upgrades.spuriousDragon.evm.newAccountCharge == NewAccountCharge.WhenValueReachesADeadDestination &&
        Upgrades.spuriousDragon.evm.touchSurvivesFailure == Set(PrecompileSet.Ripemd160) &&
        Upgrades.spuriousDragon.execution.touchedEmptyAccountsAreDeleted,
      "the upgrade that adopts EIP-161 does not carry one of its clauses"
    )

  it should "have carried none of them at the upgrade before it" in
    // The control. Without it the case above holds for a network that had them
    // all along, and no earlier proposal sets any of the four.
    assert(
      Upgrades.tangerineWhistle.evm.createdAccountNonce == UInt64.Zero &&
        Upgrades.tangerineWhistle.evm.newAccountCharge == NewAccountCharge.WhenTheDestinationIsAbsent &&
        Upgrades.tangerineWhistle.evm.touchSurvivesFailure.isEmpty &&
        !Upgrades.tangerineWhistle.execution.touchedEmptyAccountsAreDeleted,
      "a clause of EIP-161 was in force before the upgrade that adopts it"
    )

  it should "run the original instruction set with nothing added at genesis" in
    // The claim that makes this a network's configuration rather than the
    // machine's: what it launched with is the root with no proposal over it, and
    // the delegating byte -- which arrived with EIP-7 -- is the observable
    // difference between that and its next rule set.
    assert(
      !Upgrades.frontier.evm.table.contains(Opcode.DelegateCall) &&
        Upgrades.homestead.evm.table.contains(Opcode.DelegateCall),
      "the genesis table already ran an operation a later proposal introduced"
    )

  it should "pay five ether for a block until the proposal that reduces it" in
    // Two sources that do not derive from one another: BLOCK_REWARD in
    // ethereum/execution-specs @ 20f7f6271 forks/frontier/fork.py, and
    // FrontierBlockReward in ethereum/go-ethereum-pow @ v1.10.26
    // consensus/ethash/consensus.go. The amount drops to two ether at the fork
    // after the one below, which is not in this build.
    assert(
      payingTheLaunchAmount.forall(rules => rules.consensus.blockReward == ether(5)),
      "a rule set below the reduction pays something other than the launch amount"
    )

  it should "carry both consensus proposals of Byzantium at the upgrade that adopts them" in
    // Asserted at the NETWORK rather than at the components, for the reason the
    // EIP-161 case above gives and more sharply: nothing outside this module
    // reads the consensus facet in production yet, so a component nothing
    // adopts leaves all three of these members exactly where they were. Three
    // members in one case on purpose -- EIP-649 is two of them, and a rule set
    // carrying the amount without the delay would claim a document it has half
    // of.
    assert(
      Upgrades.byzantium.consensus.difficultyAdjustment == DifficultyAdjustment.Eip100 &&
        Upgrades.byzantium.consensus.blockReward == ether(3) &&
        Upgrades.byzantium.consensus.difficultyBombDelay == BigInt(3000000),
      "the upgrade that adopts EIP-100 and EIP-649 does not carry one of their values"
    )

  it should "answer natively at the fifth address from the upgrade that adopts EIP-198" in
    // Asserted at the NETWORK for the reason the two cases above give. The
    // component's own spec certifies the delta and would pass with the
    // component adopted by nothing, which for a native means every height on
    // this network running an empty account's code where the network answers.
    assert(
      Upgrades.byzantium.evm.precompiles
        .at(PrecompileSet.ModExp)
        .contains(
          Precompile.ModExp(
            Upgrades.byzantium.evm.schedule.precompileModExpDivisor,
            Upgrades.byzantium.evm.schedule.precompileModExpFloor,
            Precompile.ModExpComplexity.Piecewise
          )
        ),
      "the upgrade that adopts EIP-198 does not place its native, or places it at another price"
    )

  it should "have answered at no such address at the upgrade before it" in
    // The control, and the wording is deliberately its own for the reason the
    // next one records.
    assert(
      Upgrades.spuriousDragon.evm.precompiles.at(PrecompileSet.ModExp).isEmpty,
      "an upgrade below the one adopting EIP-198 already answered at that address"
    )

  it should "answer natively at the three curve addresses from the upgrade that adopts EIP-196 and EIP-197" in
    // Asserted at the NETWORK for the reason above: each component's own spec
    // passes with that component adopted by nothing.
    assert(
      Upgrades.byzantium.evm.precompiles
        .at(PrecompileSet.AltBn128Add)
        .contains(Precompile.AltBn128Add(Upgrades.byzantium.evm.schedule.precompileAltBn128Add)) &&
        Upgrades.byzantium.evm.precompiles
          .at(PrecompileSet.AltBn128Mul)
          .contains(Precompile.AltBn128Mul(Upgrades.byzantium.evm.schedule.precompileAltBn128Mul)) &&
        Upgrades.byzantium.evm.precompiles
          .at(PrecompileSet.AltBn128PairingCheck)
          .contains(
            Precompile.AltBn128PairingCheck(
              Upgrades.byzantium.evm.schedule.precompileAltBn128PairingBase,
              Upgrades.byzantium.evm.schedule.precompileAltBn128PairingPerPoint
            )
          ),
      "the upgrade that adopts the two curve documents does not place a native, or places one at another price"
    )

  it should "have answered at none of those three at the upgrade before it" in
    assert(
      Seq(PrecompileSet.AltBn128Add, PrecompileSet.AltBn128Mul, PrecompileSet.AltBn128PairingCheck)
        .forall(address => Upgrades.spuriousDragon.evm.precompiles.at(address).isEmpty),
      "an upgrade below the ones adopting the curve documents already answered at one of those addresses"
    )

  it should "run eight natives at that upgrade, having launched with four" in
    // The count rather than the membership, which the two cases above already
    // state. What this adds is that nothing ELSE arrived: a delta reaching an
    // address no document names would leave the membership assertions passing.
    assert(
      Upgrades.byzantium.evm.precompiles.size == 8 && Upgrades.frontier.evm.precompiles.size == 4,
      "this network runs some other number of natives at the upgrade that completes Byzantium"
    )

  it should "have carried none of their three values at the upgrade before it" in
    // The control. Without it the case above holds for a network that had them
    // all along, and no earlier proposal sets any of the three -- EIP-2 moves
    // the selector, but to its own case rather than to this one.
    //
    // The wording is deliberately not the EIP-161 control's above. ScalaTest
    // keys a test on its subject and predicate together, so two `it should`
    // clauses under one subject with the same words are one duplicate name and
    // abort the whole suite at registration rather than failing a case.
    assert(
      Upgrades.spuriousDragon.consensus.difficultyAdjustment == DifficultyAdjustment.Eip2 &&
        Upgrades.spuriousDragon.consensus.blockReward == ether(5) &&
        Upgrades.spuriousDragon.consensus.difficultyBombDelay == BigInt(0),
      "a value of EIP-100 or EIP-649 was in force before the upgrade that adopts it"
    )

  it should "have a receipt state its transaction's outcome from the upgrade that adopts EIP-658" in
    // Asserted at the NETWORK for the reason the two cases above give: nothing
    // sets this member except a component, so a component nothing adopts leaves
    // it exactly where it was and every reader of the flag goes on answering
    // the earlier fork's shape.
    assert(
      Upgrades.byzantium.execution.receiptCarriesStatus,
      "the upgrade that adopts EIP-658 leaves a receipt carrying the root it replaces"
    )

  it should "have had a receipt carrying a root at every upgrade below that one" in
    // The control, over every earlier composition rather than the one before it.
    // No proposal below this sets the member, so a flag switched on anywhere in
    // the chain of compositions would make the case above hold for a network
    // that never adopted the document -- and only the whole chain can say so.
    assert(
      belowTheStatusByte.forall(!_.execution.receiptCarriesStatus),
      "a rule set below the upgrade that adopts EIP-658 already carries a status"
    )

  it should "credit a beneficiary even where the amount is zero, at every height this build reaches" in
    // This build now reaches the fork besu flips this at:
    // .skipZeroBlockRewards(false) at its Frontier definition becomes true at
    // spuriousDragonDefinition, because from there an empty account a zero
    // credit created has to be deleted again. The specification takes the other
    // route and keeps crediting unconditionally --
    // ethereum/execution-specs @ ccaaaba58 forks/spurious_dragon/fork.py's
    // pay_rewards calls create_ether with no zero check, exactly as its Frontier
    // copy does -- and reaches the same observable through that deletion. The
    // two agree here for a third reason as well: no composition below states an
    // amount of zero, so none of them can produce the case this member is
    // about, whatever it is set to.
    assert(
      composed.forall(rules => rules.consensus.zeroRewardCreditsBeneficiary),
      "declining to credit before empty accounts are deleted would leave a leaf out of the state trie"
    )

  "the composition this network calls Istanbul" should "record exactly the six proposals it adopted, in order" in
    // Stated relative to the upgrade below rather than as the whole journal, so
    // a failure names what this composition did rather than restating every
    // proposal adopted since genesis. Equality rather than a suffix check: a
    // seventh proposal adopted here, or one of the six dropped, has to fail.
    assert(
      Upgrades.istanbul.components == Upgrades.petersburg.components ++ istanbulProposals,
      "this composition's recorded components are not the six it adopted, or are not in the order it adopted them"
    )

  it should "write the machine's facet and no other" in
    // The structural claim of this upgrade, asserted as IDENTITY rather than as
    // equality: all six components are built through the constructor that
    // cannot reach past the machine, so the other three facets must arrive here
    // as the very values the upgrade below holds. An equal copy would pass a
    // value comparison and would mean a delta had rebuilt a facet it does not
    // name.
    //
    // Measured rather than assumed: ethereum/go-ethereum-pow @ v1.10.26 --
    // geth while it still ran proof-of-work, so the tree where a consensus rule
    // would be -- names this upgrade in no non-test source under consensus/,
    // against Constantinople 8, Byzantium 11 and Homestead 11.
    assert(
      (Upgrades.istanbul.consensus eq Upgrades.petersburg.consensus) &&
        (Upgrades.istanbul.execution eq Upgrades.petersburg.execution) &&
        (Upgrades.istanbul.admission eq Upgrades.petersburg.admission),
      "an upgrade whose every component is confined to the machine rebuilt a facet outside it"
    )

  it should "pay two ether for a block, which is the amount it inherits rather than one it sets" in
    // The identity case above already says this facet arrives unchanged, and a
    // comparison against the upgrade below would say no more than that a second
    // time. What this adds is the FIGURE, because an assertion that two rule
    // sets agree goes on agreeing with a mutation that moves both.
    //
    // Two ether is EIP-1234's, adopted at Constantinople; this upgrade adopts
    // no consensus proposal and leaves it alone. Byzantium is the nearest
    // upgrade that moved it, from five to three, and this figure is neither.
    assert(
      Upgrades.istanbul.consensus.blockReward == ether(2),
      "an upgrade that names no consensus proposal changed what a block pays its producer"
    )

  it should "answer natively at the compression address from this upgrade" in
    // Asserted at the NETWORK rather than at the component: EIP-152's own spec
    // certifies the delta and would pass with the component adopted by nothing,
    // which for a native means every height on this network running an empty
    // account's code where the network is meant to answer.
    assert(
      Upgrades.istanbul.evm.precompiles
        .at(PrecompileSet.Blake2f)
        .contains(Precompile.Blake2f(Upgrades.istanbul.evm.schedule.precompileBlake2fPerRound)),
      "the upgrade that adopts EIP-152 does not place its native, or places it at another price"
    )

  it should "have answered at no compression address at the upgrade before it" in
    assert(
      Upgrades.petersburg.evm.precompiles.at(PrecompileSet.Blake2f).isEmpty,
      "an upgrade below the one adopting EIP-152 already answered at that address"
    )

  it should "run nine natives here, having run eight at the upgrade below" in
    // The count rather than the membership. What it adds is that nothing ELSE
    // arrived and nothing left: EIP-1108 reprices three natives in place, so a
    // delta that added rather than replaced would leave the membership cases
    // passing and this one failing.
    assert(
      Upgrades.istanbul.evm.precompiles.size == 9 && Upgrades.petersburg.evm.precompiles.size == 8,
      "this network runs some other number of natives at the upgrade that completes Istanbul"
    )

  it should "charge this network's own reduced prices at the three curve addresses" in
    // The remedy for a blindness a cross-network comparison cannot cover. Every
    // figure EIP-1108 moves from is stated identically by both networks this
    // repository configures, so an assertion that the two agree would go on
    // agreeing with a mutation that moved both. These are this network's
    // literals, read from the document's own Specification table.
    assert(
      Upgrades.istanbul.evm.precompiles.at(PrecompileSet.AltBn128Add).contains(Precompile.AltBn128Add(BigInt(150))) &&
        Upgrades.istanbul.evm.precompiles
          .at(PrecompileSet.AltBn128Mul)
          .contains(Precompile.AltBn128Mul(BigInt(6000))) &&
        Upgrades.istanbul.evm.precompiles
          .at(PrecompileSet.AltBn128PairingCheck)
          .contains(Precompile.AltBn128PairingCheck(BigInt(45000), BigInt(34000))),
      "a curve native at this upgrade charges something other than the figure this network states"
    )

  it should "run the two operations this upgrade introduces, at this network's own prices" in
    // Both are priced from a tier rather than from a figure of their own, and
    // this network states the base tier at 2 and the low tier at 5. Asserted as
    // literals for the reason the case above gives: the tiers are shared, so
    // only a figure says what a node here charges.
    assert(
      settledCost(Upgrades.istanbul.evm.table, Opcode.ChainId).contains(BigInt(2)) &&
        settledCost(Upgrades.istanbul.evm.table, Opcode.SelfBalance).contains(BigInt(5)),
      "an operation this upgrade introduces is absent, or is charged something other than its tier's value here"
    )

  it should "have run neither of those two at the upgrade before it" in
    // The control. Without it the case above holds for a network that ran both
    // all along, and an operation present below the document that introduces it
    // would make that document's delta unobservable.
    assert(
      !Upgrades.petersburg.evm.table.contains(Opcode.ChainId) &&
        !Upgrades.petersburg.evm.table.contains(Opcode.SelfBalance),
      "an upgrade below the one that introduces these operations already ran one of them"
    )

  it should "charge this network's own raised prices for the three trie-size operations" in
    // EIP-1884's half, as literals rather than as a comparison. Two of the
    // three are read only where the table is built, so these are the entries a
    // record-only repricing would leave behind.
    assert(
      settledCost(Upgrades.istanbul.evm.table, Opcode.SLoad).contains(BigInt(800)) &&
        settledCost(Upgrades.istanbul.evm.table, Opcode.Balance).contains(BigInt(700)) &&
        settledCost(Upgrades.istanbul.evm.table, Opcode.ExtCodeHash).contains(BigInt(700)),
      "an operation EIP-1884 reprices charges something other than the figure this network states"
    )

  it should "state the raised SLOAD price on its record as well as in its entry" in
    // The record and the entry are two homes for one figure and only one of
    // them is read at spend time, so agreeing here is the property that keeps a
    // storage read priced the same whichever way it is reached.
    assert(
      Upgrades.istanbul.evm.schedule.storageLoad == BigInt(800),
      "the record disagrees with the entry built from it about what a storage read costs"
    )

  it should "carry both halves of the figure two documents here call SLOAD_GAS" in
    // The composition-level statement of the split each of the two documents
    // asserts on its own. EIP-1884 moves the operation's price and EIP-2200
    // moves the two members carrying the same quantity inside the storage-write
    // calculation; adopting one and not the other leaves a schedule that
    // compiles and charges a storage read one price through SLOAD and another
    // through SSTORE, which nothing else compares.
    assert(
      Upgrades.istanbul.evm.schedule.storageLoad == BigInt(800) &&
        Upgrades.istanbul.evm.schedule.netStorageNoop == BigInt(800) &&
        Upgrades.istanbul.evm.schedule.netStorageDirty == BigInt(800),
      "one of the three fields this upgrade's two documents move to 800 was left behind"
    )

  it should "price storage with the sentry in front of the net scheme" in
    assert(
      Upgrades.istanbul.evm.storageMetering == StorageMetering.NetWithSentry,
      "the upgrade that adopts EIP-2200 does not put its scheme in force"
    )

  it should "have priced storage the legacy way at the upgrade before it" in
    // The control, and the specific earlier case rather than "not
    // NetWithSentry": this network reached that state by adopting the net
    // scheme at Constantinople and withdrawing it at Petersburg.
    assert(
      Upgrades.petersburg.evm.storageMetering == StorageMetering.Legacy,
      "the upgrade below the one adopting EIP-2200 was already metering storage some other way"
    )

  it should "carry the two refunds EIP-2200 derives from the moved variable" in
    assert(
      Upgrades.istanbul.evm.schedule.refundNetStorageResetFromZero == BigInt(19200) &&
        Upgrades.istanbul.evm.schedule.refundNetStorageReset == BigInt(4200),
      "a refund EIP-2200 defines as a difference from the moved variable did not move with it"
    )

  it should "charge sixteen for a non-zero byte of transaction data and four for a zero one" in
    // Both figures in one case on purpose: the document changes the RATIO
    // between them, so a rule set carrying the reduction without the price it
    // is a reduction against would claim a document it has half of.
    assert(
      Upgrades.istanbul.evm.schedule.transactionDataPerNonZeroByte == BigInt(16) &&
        Upgrades.istanbul.evm.schedule.transactionDataPerZeroByte == BigInt(4),
      "this upgrade charges something other than the two figures EIP-2028 leaves this network with"
    )

  it should "have charged sixty-eight for a non-zero byte at the upgrade before it" in
    // The control. Without it the case above holds for a network that stated
    // the reduced price all along, and no proposal below this one moves it.
    assert(
      Upgrades.petersburg.evm.schedule.transactionDataPerNonZeroByte == BigInt(68),
      "an upgrade below the one adopting EIP-2028 already charged its reduced figure"
    )

  "the composition this network calls Muir Glacier" should "record exactly the one proposal it adopted" in
    // Stated relative to the upgrade below rather than as the whole journal, so
    // a failure names what this composition did rather than restating every
    // proposal adopted since genesis. `ethereum/EIPs` @ `dbfa6bee`, EIP-2387
    // *Hardfork Meta: Muir Glacier* (Final) lists one entry under *Included
    // EIPs*; its `requires:` frontmatter names EIP-1679 as well, which is the
    // upgrade below's own meta proposal.
    assert(
      Upgrades.muirGlacier.components == Upgrades.istanbul.components :+ ProposalId.Eip(2384),
      "this composition's recorded components are not the one it adopted"
    )

  it should "hold the exponential term back by nine million blocks" in
    // THE CASE THIS SECTION EXISTS FOR, and it is asserted at the NETWORK rather
    // than at the component for the reason the EIP-161 and Byzantium cases above
    // give, more sharply than either: `Eip2384Spec` certifies the delta and
    // passes with the component adopted by nothing, and the published difficulty
    // corpus certifies the FIGURE while reading no schedule at all. Between them
    // a rule set that never adopted this document is invisible -- and the next
    // upgrade this network takes composes from THIS one, so it would inherit
    // 5,000,000 into a fork whose delay is 9,000,000.
    assert(
      Upgrades.muirGlacier.consensus.difficultyBombDelay == BigInt(9000000),
      "the upgrade that adopts EIP-2384 does not carry its figure"
    )

  it should "have held it back by five million at the upgrade before it" in
    // The control, stated as the specific earlier figure. Without it the case
    // above holds for a network that carried 9,000,000 all along, and the
    // proposal below that sets it is EIP-1234's, adopted at Constantinople.
    assert(
      Upgrades.istanbul.consensus.difficultyBombDelay == BigInt(5000000),
      "an upgrade below the one adopting EIP-2384 already held the term back by its figure"
    )

  it should "write the consensus facet and no other" in
    // The mirror of the identity case Istanbul carries, and the two together are
    // what make this upgrade's whole content checkable: that one asserts all six
    // of its components stay inside the machine, this one asserts this
    // document's single component stays outside it. Measured rather than
    // assumed: `besu-eth/besu` @ `fdf1247c6`'s `muirGlacierDefinition` returns
    // `istanbulDefinition(...)` with a difficulty calculator and a fork label
    // appended, and a label is not a rule.
    assert(
      (Upgrades.muirGlacier.evm eq Upgrades.istanbul.evm) &&
        (Upgrades.muirGlacier.execution eq Upgrades.istanbul.execution) &&
        (Upgrades.muirGlacier.admission eq Upgrades.istanbul.admission),
      "an upgrade whose one component names a consensus figure rebuilt a facet outside that one"
    )

  it should "pay two ether for a block, which is the amount it inherits rather than one it sets" in
    // The amount is EIP-1234's, adopted at Constantinople alongside the delay
    // this document extends -- so a component built by copying that document's
    // two-part shape would cut it again here. An assertion that the two rule
    // sets agree would not catch that, because a mutation moving both goes on
    // agreeing; this states the FIGURE.
    assert(
      Upgrades.muirGlacier.consensus.blockReward == ether(2),
      "an upgrade whose one document states no reward changed what a block pays its producer"
    )

  "the composition this network calls Berlin" should "record exactly the four proposals it adopted" in
    // Stated relative to the upgrade below rather than as the whole journal, so
    // a failure names what this composition did. A FIFTH was in this upgrade and
    // was taken out forty days before it ran -- EIP-2315, removed in
    // `ethereum/execution-specs` @ `7d3d203a8` -- and it never activated
    // anywhere, so unlike EIP-1283 at Constantinople there is no adoption to
    // record and no second rule set withdrawing it.
    assert(
      Upgrades.berlin.components == Upgrades.muirGlacier.components ++ berlinProposals,
      "this composition's recorded components are not the four it adopted"
    )

  it should "price reaching state by whether this transaction has reached it" in
    // THE CASE THIS SECTION EXISTS FOR. `Eip2929Spec` certifies the delta and
    // passes with the component adopted by nothing; only a case reading the
    // composition can tell a correct wiring from a component that was written
    // and never adopted.
    assert(
      Upgrades.berlin.evm.stateAccessMetering == StateAccessMetering.WarmCold,
      "the upgrade that adopts EIP-2929 does not carry its rule"
    )

  it should "have priced every reach alike at the upgrade before it" in
    assert(
      Upgrades.muirGlacier.evm.stateAccessMetering == StateAccessMetering.Settled,
      "an upgrade below the one adopting EIP-2929 already ran its scheme"
    )

  it should "carry the three figures that scheme spends" in
    // Stated as literals at the composition, because the delta and its own spec
    // could agree on three wrong numbers and this is the reading a node makes.
    assert(
      Upgrades.berlin.evm.schedule.warmAccess == BigInt(100) &&
        Upgrades.berlin.evm.schedule.coldAccountAccess == BigInt(2600) &&
        Upgrades.berlin.evm.schedule.coldStorageAccess == BigInt(2100),
      "the upgrade carries a figure other than the document's"
    )

  it should "carry the five figures of the storage scheme that document re-derives" in
    // The composite-definition hazard EIP-2929 states about itself, read at the
    // composition. Four of the five are EIP-2200's settled literals and the
    // fifth is what SSTORE_RESET_GAS becomes; a delta applying the new constants
    // without re-running the derivations leaves this upgrade internally
    // inconsistent and passes everything that does not execute a store.
    assert(
      Upgrades.berlin.evm.schedule.netStorageNoop == BigInt(100) &&
        Upgrades.berlin.evm.schedule.netStorageDirty == BigInt(100) &&
        Upgrades.berlin.evm.schedule.netStorageClean == BigInt(2900) &&
        Upgrades.berlin.evm.schedule.refundNetStorageResetFromZero == BigInt(19900) &&
        Upgrades.berlin.evm.schedule.refundNetStorageReset == BigInt(2800),
      "a derivation that reads SLOAD_GAS was not re-run against this document's terms"
    )

  it should "let a block carry the declaring transaction format" in
    // The second case only the composition can make, and the sharper of the two:
    // EIP-2930's component is the first here to reach two facets, so a component
    // built from the machine-scoped constructor would apply its prices and admit
    // nothing -- which every case in `Eip2930Spec` would catch and no case
    // reading only the machine would.
    assert(
      Upgrades.berlin.admission.admittedTypes == Set(TransactionType.Legacy, TransactionType.AccessList),
      "the upgrade that adopts EIP-2930 does not admit the format it defines"
    )

  it should "have carried only the untagged format at the upgrade before it" in
    assert(
      Upgrades.muirGlacier.admission.admittedTypes == Set(TransactionType.Legacy),
      "an upgrade below the one adopting EIP-2930 already carried its format"
    )

  it should "charge modular exponentiation under this document's own scheme" in
    // The entry rather than the record, because a precompile's price is copied
    // into its entry when the entry is built: an upgrade stating 3 and charging
    // 20 is what reading only the schedule here would miss.
    assert(
      Upgrades.berlin.evm.precompiles
        .at(PrecompileSet.ModExp)
        .contains(Precompile.ModExp(BigInt(3), BigInt(200), Precompile.ModExpComplexity.SquaredWordCount)),
      "the upgrade that adopts EIP-2565 charges the entry it inherited"
    )

  it should "hold the exponential term back by the nine million blocks it inherits" in
    // NOT restated by any of this upgrade's four documents, and carried from the
    // upgrade below. `ethereum/execution-specs` @ `20f7f6271` declares
    // `BOMB_DELAY_BLOCKS = 9000000` in `forks/berlin/fork.py:70` as well as in
    // `forks/muir_glacier/fork.py:65`, so a composition reaching past that
    // upgrade would run 5,000,000 here -- which compiles, and which no state
    // tier can see.
    assert(
      Upgrades.berlin.consensus.difficultyBombDelay == BigInt(9000000),
      "this composition was built from an upgrade below the one that set the delay"
    )

  it should "write the machine's rules and admission's, and no other facet" in
    // Three of the four are confined to the machine and the fourth also reaches
    // admission. Nothing here touches consensus or settlement, so those two
    // survive as the SAME values rather than as equal copies.
    assert(
      (Upgrades.berlin.consensus eq Upgrades.muirGlacier.consensus) &&
        (Upgrades.berlin.execution eq Upgrades.muirGlacier.execution),
      "an upgrade whose components name the machine and admission rebuilt a third facet"
    )

  it should "pay two ether for a block, which is the amount it inherits rather than one it sets" in
    assert(
      Upgrades.berlin.consensus.blockReward == ether(2),
      "an upgrade whose four documents state no reward changed what a block pays its producer"
    )

  // ── London ────────────────────────────────────────────────────────────────

  "the upgrade adopting London's five" should "record all five in its journal" in
    assert(
      Vector(1559, 3198, 3529, 3541, 3554).forall(n => Upgrades.london.components.contains(ProposalId.Eip(n))),
      "the journal is what a schedule entry is read from, and a missing entry is silent"
    )

  it should "let a block carry the capped transaction format" in
    assert(
      Upgrades.london.admission.admittedTypes ==
        Set(TransactionType.Legacy, TransactionType.AccessList, TransactionType.DynamicFee),
      "the upgrade that adopts EIP-1559 does not admit the format it defines"
    )

  it should "give this network its first fee market" in
    // The case only the composition can make. EIP-1559's component is the first
    // here to write the header facet, so one built from the admission-only
    // shape its sibling documents use would admit the format and set no charge
    // -- and a capped offer with no market to resolve it against is the
    // configuration BlockProcessor raises on rather than returns.
    assert(
      Upgrades.london.header.feeMarket.isDefined,
      "the upgrade that adopts EIP-1559 sets no charge, so nothing prices the format it just admitted"
    )

  it should "have had no fee market at the upgrade before it" in
    assert(
      Upgrades.berlin.header.feeMarket.isEmpty,
      "an upgrade below the one adopting EIP-1559 already set a charge"
    )

  it should "hold the exponential term back by nine million seven hundred thousand blocks" in
    // The figure is cumulative rather than a delta, so composing this upgrade
    // from anywhere below Berlin carries a different starting point into the
    // same assignment and compiles. No state-fixture tier at any fork could
    // observe it, because difficulty is settled in a header.
    assert(
      Upgrades.london.consensus.difficultyBombDelay == BigInt(9700000),
      "EIP-3554 states the cumulative figure and the upgrade below it holds nine million"
    )

  it should "have held it back by nine million at the upgrade before it" in
    assert(
      Upgrades.berlin.consensus.difficultyBombDelay == BigInt(9000000),
      "otherwise the assertion above holds for a reason that is not this document"
    )

  it should "keep the block reward the upgrade before it paid" in
    // EIP-1559 moves where a fee GOES and not what a block is paid for
    // producing. An upgrade that changed both would be two documents folded
    // together.
    assert(
      Upgrades.london.consensus.blockReward == Upgrades.berlin.consensus.blockReward,
      "the fee market changes the fee, never the reward"
    )
