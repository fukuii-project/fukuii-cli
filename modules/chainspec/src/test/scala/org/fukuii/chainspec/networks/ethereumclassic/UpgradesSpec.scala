package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.proposals.eip.{Eip1234, Eip1283, Eip1884, Eip2200}
import org.fukuii.chainspec.{DifficultyAdjustment, ProposalId}
import org.fukuii.evm.{Cost, Opcode, OpcodeTable, Operation, Precompile, PrecompileSet, StorageMetering}
import org.scalatest.flatspec.AnyFlatSpec

/** Properties every rule set this network composed has to hold.
  *
  * What each proposal of one upgrade settles is a matrix over that upgrade's
  * component list, and lives in [[UpgradesPropSpec]]; this holds the facts that
  * are about a composition as a whole.
  */
class UpgradesSpec extends AnyFlatSpec:

  private val composed =
    Vector(
      Upgrades.frontier,
      Upgrades.homestead,
      Upgrades.gasReprice,
      Upgrades.dieHard,
      Upgrades.gotham,
      Upgrades.defuse,
      Upgrades.atlantis,
      Upgrades.agharta,
      Upgrades.phoenix
    )

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  "every rule set this network composes" should "leave SELFDESTRUCT working out its own price" in {
    // Nothing reads this entry's cost, so a proposal settling one would compile,
    // pass, and be charged the schedule's figure instead -- silently, and in the
    // one direction the interpreter's own unpriced-operation guard cannot see.
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
        Upgrades.gasReprice.components ==
        Vector(ProposalId.Eip(7), ProposalId.Eip(2), ProposalId.Eip(150)) &&
        Upgrades.dieHard.components ==
        Vector(
          ProposalId.Eip(7),
          ProposalId.Eip(2),
          ProposalId.Eip(150),
          ProposalId.Eip(155),
          ProposalId.Eip(160),
          ProposalId.Ecip(1010)
        ) &&
        Upgrades.gotham.components ==
        Vector(
          ProposalId.Eip(7),
          ProposalId.Eip(2),
          ProposalId.Eip(150),
          ProposalId.Eip(155),
          ProposalId.Eip(160),
          ProposalId.Ecip(1010),
          ProposalId.Ecip(1017),
          ProposalId.Ecip(1039)
        ) &&
        Upgrades.defuse.components ==
        Vector(
          ProposalId.Eip(7),
          ProposalId.Eip(2),
          ProposalId.Eip(150),
          ProposalId.Eip(155),
          ProposalId.Eip(160),
          ProposalId.Ecip(1010),
          ProposalId.Ecip(1017),
          ProposalId.Ecip(1039),
          ProposalId.Ecip(1041)
        ) &&
        Upgrades.atlantis.components ==
        Vector(
          ProposalId.Eip(7),
          ProposalId.Eip(2),
          ProposalId.Eip(150),
          ProposalId.Eip(155),
          ProposalId.Eip(160),
          ProposalId.Ecip(1010),
          ProposalId.Ecip(1017),
          ProposalId.Ecip(1039),
          ProposalId.Ecip(1041),
          ProposalId.Eip(161),
          ProposalId.Eip(170),
          ProposalId.Eip(100),
          ProposalId.Eip(140),
          ProposalId.Eip(211),
          ProposalId.Eip(214),
          ProposalId.Eip(658),
          ProposalId.Eip(198),
          ProposalId.Eip(196),
          ProposalId.Eip(197)
        ) &&
        Upgrades.agharta.components == Upgrades.atlantis.components ++
        Vector(ProposalId.Eip(145), ProposalId.Eip(1014), ProposalId.Eip(1052)) &&
        Upgrades.phoenix.components == Upgrades.agharta.components ++
        Vector(
          ProposalId.Eip(152),
          ProposalId.Eip(1108),
          ProposalId.Eip(1344),
          ProposalId.Eip(1884),
          ProposalId.Eip(2028),
          ProposalId.Eip(2200)
        ),
      "a composition's recorded components are not the ones it adopted"
    )

  it should "differ from the rules it was built on by the record alone, at the emission step" in
    // The composition ECIP-1017 and ECIP-1039 produce: both deltas leave every
    // facet as it was, so the two rule sets share all four by reference and are
    // told apart only by what they record having adopted. Reference equality is
    // what makes that testable -- a delta returning an equal copy would satisfy
    // a value comparison and fail this, which is the direction that matters,
    // because a copy is what a delta reaching a facet by accident produces.
    assert(
      (Upgrades.gotham.evm eq Upgrades.dieHard.evm) &&
        (Upgrades.gotham.execution eq Upgrades.dieHard.execution) &&
        (Upgrades.gotham.admission eq Upgrades.dieHard.admission) &&
        (Upgrades.gotham.consensus eq Upgrades.dieHard.consensus) &&
        Upgrades.gotham.components != Upgrades.dieHard.components,
      "the emission step either moved a rule value or did not record itself, and it must do exactly the second"
    )

  it should "carry the rule's proposal series rather than the document this network adopted it by" in
    // Two levels, and the schedule holds the other one: the rule is EIP-150 and
    // the document is ECIP-1015, which is this network's label for the upgrade
    // and not a component of it.
    assert(
      !Upgrades.gasReprice.components.contains(ProposalId.Ecip(1015)),
      "an adoption document reached a component list, which records what determines the rules"
    )

  it should "run the original instruction set with nothing added at genesis" in
    // What it launched with is the root with no proposal over it, and the
    // delegating byte -- which arrived with EIP-7 -- is the observable
    // difference between that and its next rule set.
    assert(
      !Upgrades.frontier.evm.table.contains(Opcode.DelegateCall) &&
        Upgrades.homestead.evm.table.contains(Opcode.DelegateCall),
      "the genesis table already ran an operation a later proposal introduced"
    )

  it should "hold five ether as the base its own ladder reduces, at every rule set this network composes" in
    // Two lineages that do not derive from one another: blockReward in
    // openethereum/openethereum @ v3.0.1 ethcore/res/ethereum/classic.json, and
    // MAX_BLOCK_REWARD = Wei.fromEth(5) in besu-eth/besu-etc @ eb4248c99
    // ClassicProtocolSpecs.java:60. It is the base ECIP-1017's ladder reduces
    // rather than a figure that proposal replaces, so a rule set holds the base
    // and org.fukuii.consensus.pow.EthashEngine computes the step from an era
    // length. Stating which heights this build reaches would go stale the next
    // time a schedule grows, in a file that change would not touch.
    assert(
      composed.forall(rules =>
        rules.consensus.blockReward == UInt256.fromBigInt(BigInt(5) * BigInt(10).pow(18)).toOption.get
      ),
      "a rule set here pays something other than the amount this network's own proposal takes as its first era"
    )

  it should "credit a beneficiary even where the amount is zero, at every height this build reaches" in
    // The same answer as any network at a height before touched empty accounts
    // are deleted, and unobservable at five ether. It is stated because the
    // field states it rather than leaving it to the default.
    assert(
      composed.forall(rules => rules.consensus.zeroRewardCreditsBeneficiary),
      "declining to credit before empty accounts are deleted would leave a leaf out of the state trie"
    )

  it should "decline EIP-649 while carrying the other consensus proposal of that fork" in
    // The record's silence is the enumeration above. This is the rules' side,
    // and the two are independent: a component's delta is an arbitrary
    // function, so a list can be right while the values it claims to have
    // produced are not. Four members in one case on purpose -- the document is
    // a reduced amount and a delayed term, the first two members are what it
    // would have moved, and the fourth is why neither half has anything here to
    // act on.
    assert(
      Upgrades.atlantis.consensus.difficultyAdjustment == DifficultyAdjustment.Eip100 &&
        Upgrades.atlantis.consensus.blockReward ==
        UInt256.fromBigInt(BigInt(5) * BigInt(10).pow(18)).toOption.get &&
        Upgrades.atlantis.consensus.difficultyBombDelay == BigInt(0) &&
        Upgrades.atlantis.consensus.difficultyBombRemovedFrom.contains(BigInt(5900000)),
      "the upgrade taking eight of that fork's nine proposals carries a value only the ninth sets"
    )

  "the upgrade above it" should "decline EIP-1283 and EIP-1234, and be moved by each if it did not" in
    // The rules' side of a decline, and the second clause of each pair is what
    // makes it an assertion rather than a coincidence. Without it, the first
    // clause is equally satisfied by a component whose delta does nothing, and
    // the test could not tell a decline from a broken proposal.
    //
    // Both components are built and adoptable, so what is asserted here is a
    // choice this composition makes rather than a gap in the vocabulary. The
    // enumeration above constrains the RECORD; this constrains the VALUES, and
    // the two are independent because a delta is an arbitrary function.
    assert(
      Upgrades.agharta.evm.storageMetering == StorageMetering.Legacy &&
        Upgrades.agharta.adopting(Eip1283.component).evm.storageMetering == StorageMetering.Net &&
        Upgrades.agharta.consensus.difficultyBombDelay == BigInt(0) &&
        Upgrades.agharta.adopting(Eip1234.component).consensus.difficultyBombDelay == BigInt(5000000) &&
        Upgrades.agharta.consensus.blockReward ==
        UInt256.fromBigInt(BigInt(5) * BigInt(10).pow(18)).toOption.get &&
        Upgrades.agharta.adopting(Eip1234.component).consensus.blockReward ==
        UInt256.fromBigInt(BigInt(2) * BigInt(10).pow(18)).toOption.get,
      "a proposal this upgrade declines is either in force at it or would not have changed it"
    )

  it should "price the three operations it adds from this network's own schedule" in
    // The figures, read off the adopted table rather than off the schedule,
    // because a fixed-price entry is settled at the moment of adoption and a
    // later edit to the schedule alone would not reach it.
    //
    // Stated as literals on THIS network's base. The proposal specs assert the
    // same two documents against the other network's, and
    // org.fukuii.chainspec.networks.SharedHistorySpec asserts the two machines
    // equal -- neither of which can see a repricing that moves both networks
    // together, and neither of which is a reading of what this network charges.
    //
    // CREATE2 is absent from this list because it works out its own price, which
    // is asserted where the operations that must NOT be settled are.
    assert(
      settledCost(Upgrades.agharta.evm.table, Opcode.Shl).contains(BigInt(3)) &&
        settledCost(Upgrades.agharta.evm.table, Opcode.Shr).contains(BigInt(3)) &&
        settledCost(Upgrades.agharta.evm.table, Opcode.Sar).contains(BigInt(3)) &&
        settledCost(Upgrades.agharta.evm.table, Opcode.ExtCodeHash).contains(BigInt(400)),
      "an operation this upgrade adds charges something other than the figure its own document publishes"
    )

  it should "leave CREATE2 working out its own price" in
    // The one of the three additions that carries no fixed figure: its charge is
    // a base plus a word-count term over the initialisation code, so a settled
    // price would be charged instead of the computation and the machine's own
    // unpriced-operation guard cannot see the difference.
    assert(
      Upgrades.agharta.evm.table.contains(Opcode.Create2) &&
        settledCost(Upgrades.agharta.evm.table, Opcode.Create2).isEmpty,
      "the operation whose charge depends on its operands was given a fixed one"
    )

  "the first upgrade this network takes whole from upstream" should
    "reach net-metered storage from the legacy scheme in one step" in
    // The composition executed rather than the reasoning restated. EIP-2200
    // writes its scheme absolutely rather than as an amendment to EIP-1283's
    // state, so this network arrives at it from Legacy where the other network's
    // journal is an adoption, a withdrawal and then this. The two clauses about
    // the record are what make that a one-step arrival rather than a value that
    // happens to match: a base that had run either proposal would carry it.
    assert(
      Upgrades.agharta.evm.storageMetering == StorageMetering.Legacy &&
        Upgrades.phoenix.evm.storageMetering == StorageMetering.NetWithSentry &&
        !Upgrades.phoenix.components.contains(ProposalId.Eip(1283)) &&
        !Upgrades.phoenix.components.contains(ProposalId.Eip(1716)),
      "this network reached net-metered storage through a proposal it never adopted, or did not reach it"
    )

  it should "carry the three figures EIP-2200 leaves alone at the values this network launched with" in
    // The document lists SSTORE_SET_GAS, SSTORE_RESET_GAS and
    // SSTORE_CLEARS_SCHEDULE as not changed, so the composition depends on the
    // base supplying them. Read against genesisPrices rather than against
    // literals: the claim is that no component between launch and here writes
    // them, which a literal would go on satisfying if one started writing the
    // same number. The literals are stated too, because carrying a figure
    // forward unchanged says nothing about it being the figure the document
    // expects.
    assert(
      Upgrades.phoenix.evm.schedule.netStorageInit == Upgrades.genesisPrices.netStorageInit &&
        Upgrades.phoenix.evm.schedule.netStorageClean == Upgrades.genesisPrices.netStorageClean &&
        Upgrades.phoenix.evm.schedule.refundNetStorageClear == Upgrades.genesisPrices.refundNetStorageClear &&
        Upgrades.phoenix.evm.schedule.netStorageInit == BigInt(20000) &&
        Upgrades.phoenix.evm.schedule.netStorageClean == BigInt(5000) &&
        Upgrades.phoenix.evm.schedule.refundNetStorageClear == BigInt(15000),
      "a figure EIP-2200 declares unchanged moved, or this network never held the value the document assumes"
    )

  it should "move one published figure into three fields, by two documents, and be neither document alone" in
    // ECIP-1086 records that this network split two of its own test networks
    // over exactly this: EIP-1884 and EIP-2200 each raise a quantity both call
    // SLOAD_GAS from 200 to 800, and they raise different fields. The last two
    // clauses are the ones that matter -- each adopts one document onto the
    // base and reads the OTHER document's field, which is the pick-and-mix
    // configuration that registry calls broken. A schedule sharing one field
    // between the two documents could not express it, so it could not refute it
    // either.
    assert(
      Upgrades.agharta.evm.schedule.storageLoad == BigInt(200) &&
        Upgrades.agharta.evm.schedule.netStorageNoop == BigInt(200) &&
        Upgrades.agharta.evm.schedule.netStorageDirty == BigInt(200) &&
        Upgrades.phoenix.evm.schedule.storageLoad == BigInt(800) &&
        Upgrades.phoenix.evm.schedule.netStorageNoop == BigInt(800) &&
        Upgrades.phoenix.evm.schedule.netStorageDirty == BigInt(800) &&
        Upgrades.agharta.adopting(Eip2200.component).evm.schedule.storageLoad == BigInt(200) &&
        Upgrades.agharta.adopting(Eip1884.component).evm.schedule.netStorageNoop == BigInt(200),
      "one document moved the other's field, or a composition of one document alone already moved both"
    )

  it should "price the operations it adds and reprices from this network's own schedule" in
    // Read off the adopted table rather than off the schedule, because a
    // fixed-price entry is settled at the moment of adoption: a repricing that
    // moved the schedule and left the entry alone would leave the record and
    // the charge disagreeing, and only the entry is what a frame is billed.
    //
    // Stated as literals on THIS network's base. The two additions are priced
    // from tiers no proposal this network adopts ever writes, which is why they
    // read 2 and 5 here and would read the same on any base carrying those
    // tiers.
    assert(
      settledCost(Upgrades.phoenix.evm.table, Opcode.SLoad).contains(BigInt(800)) &&
        settledCost(Upgrades.phoenix.evm.table, Opcode.Balance).contains(BigInt(700)) &&
        settledCost(Upgrades.phoenix.evm.table, Opcode.ExtCodeHash).contains(BigInt(700)) &&
        settledCost(Upgrades.phoenix.evm.table, Opcode.ChainId).contains(BigInt(2)) &&
        settledCost(Upgrades.phoenix.evm.table, Opcode.SelfBalance).contains(BigInt(5)),
      "an operation this upgrade adds or reprices charges something other than the figure its own document publishes"
    )

  it should "place the ninth native and reprice the three the upgrade below it carried" in
    // The precompile set's side of the same hazard, and the first time this
    // network reprices one at all. The three entries are rebuilt from the moved
    // record rather than left reading the schedule, so an entry carrying the
    // old figure beside a schedule carrying the new one is what this refutes.
    //
    // The absence at the upgrade below is what makes the first clause an
    // adoption rather than a value the base already held -- and it is a reading
    // of this composition, not of every client: one reference build installs
    // this registry 927,839 blocks lower, which Upgrades.phoenix records.
    assert(
      Upgrades.agharta.evm.precompiles.at(PrecompileSet.Blake2f).isEmpty &&
        Upgrades.phoenix.evm.precompiles.at(PrecompileSet.Blake2f).contains(Precompile.Blake2f(BigInt(1))) &&
        Upgrades.phoenix.evm.precompiles
          .at(PrecompileSet.AltBn128Add)
          .contains(Precompile.AltBn128Add(BigInt(150))) &&
        Upgrades.phoenix.evm.precompiles
          .at(PrecompileSet.AltBn128Mul)
          .contains(Precompile.AltBn128Mul(BigInt(6000))) &&
        Upgrades.phoenix.evm.precompiles
          .at(PrecompileSet.AltBn128PairingCheck)
          .contains(Precompile.AltBn128PairingCheck(BigInt(45000), BigInt(34000))),
      "a native this upgrade places or reprices is absent, or answers at the price the upgrade below it charged"
    )
