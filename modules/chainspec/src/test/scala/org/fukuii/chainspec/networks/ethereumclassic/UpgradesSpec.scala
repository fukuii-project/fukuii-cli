package org.fukuii.chainspec.networks.ethereumclassic

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.proposals.eip.{
  Eip1234,
  Eip1283,
  Eip1559,
  Eip1884,
  Eip2200,
  Eip2929,
  Eip3198,
  Eip3529,
  Eip3541,
  Eip3554,
  Eip3651,
  Eip3855,
  Eip3860,
  Eip4399,
  Eip4895
}
import org.fukuii.chainspec.{DifficultyAdjustment, ProposalId}
import org.fukuii.evm.{
  BlockRandomness,
  Cost,
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
      Upgrades.phoenix,
      Upgrades.thanos,
      Upgrades.magneto,
      Upgrades.mystique,
      Upgrades.spiral
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
        ) &&
        Upgrades.thanos.components == Upgrades.phoenix.components ++ Vector(ProposalId.Ecip(1099)) &&
        Upgrades.magneto.components == Upgrades.thanos.components ++
        Vector(
          ProposalId.Eip(2565),
          ProposalId.Eip(2718),
          ProposalId.Eip(2929),
          ProposalId.Eip(2930)
        ) &&
        Upgrades.mystique.components == Upgrades.magneto.components ++
        Vector(ProposalId.Eip(3529), ProposalId.Eip(3541)) &&
        Upgrades.spiral.components == Upgrades.mystique.components ++
        Vector(ProposalId.Eip(3651), ProposalId.Eip(3855), ProposalId.Eip(3860)),
      "a composition's recorded components are not the ones it adopted"
    )

  "the epoch calibration" should "be adopted at this network's own height, and at no upgrade below it" in
    // The assertion this upgrade exists to carry, and the only one that can
    // catch it: the value sizes a mining epoch, so it reaches a seal rather
    // than a state root, and no state-transition or difficulty tier this build
    // reads carries a label for it at all. What would otherwise be untested is
    // whether the schedule supplies the figure -- a field every rule set left
    // empty would compute a wrong DAG size for every block above the height and
    // report nothing.
    //
    // Three arms rather than one: the value adopted, the base it extends, and a
    // rule set below both. The base is what makes it a change rather than a
    // restatement, and the third arm is the negative control -- without it an
    // implementation that set the field everywhere would pass.
    assert(
      Upgrades.thanos.consensus.ecip1099Activation.contains(BigInt(11700000)) &&
        Upgrades.phoenix.consensus.ecip1099Activation.isEmpty &&
        Upgrades.frontier.consensus.ecip1099Activation.isEmpty,
      "the height ECIP-1099 states for this network is not the one its rule set carries"
    )

  it should "leave every facet but the consensus one exactly as it found them" in
    // Reference equality, for the reason the emission-step case below gives: a
    // delta that reached a facet by accident would produce an equal copy, which
    // a value comparison cannot tell from the original.
    assert(
      (Upgrades.thanos.evm eq Upgrades.phoenix.evm) &&
        (Upgrades.thanos.execution eq Upgrades.phoenix.execution) &&
        (Upgrades.thanos.admission eq Upgrades.phoenix.admission) &&
        (Upgrades.thanos.consensus ne Upgrades.phoenix.consensus),
      "ECIP-1099 reached a facet it does not name, or failed to reach the one it does"
    )

  "the composition this network calls Magneto" should "price reaching state by whether this transaction has reached it" in
    // The case only a composition can make: `Eip2929Spec` certifies the delta
    // and passes with the component adopted by nothing.
    assert(
      Upgrades.magneto.evm.stateAccessMetering == StateAccessMetering.WarmCold,
      "the upgrade that adopts EIP-2929 does not carry its rule"
    )

  it should "have priced every reach alike at the upgrade before it" in
    assert(
      Upgrades.thanos.evm.stateAccessMetering == StateAccessMetering.Settled,
      "an upgrade below the one adopting EIP-2929 already ran its scheme"
    )

  it should "carry the three figures that scheme spends" in
    assert(
      Upgrades.magneto.evm.schedule.warmAccess == BigInt(100) &&
        Upgrades.magneto.evm.schedule.coldAccountAccess == BigInt(2600) &&
        Upgrades.magneto.evm.schedule.coldStorageAccess == BigInt(2100),
      "the upgrade carries a figure other than the document's"
    )

  it should "carry the five figures of the storage scheme that document re-derives" in
    // Four are EIP-2200's settled literals and the fifth is what SSTORE_RESET_GAS
    // becomes. A delta applying the new constants without re-running the
    // derivations leaves this upgrade internally inconsistent and passes
    // everything that does not execute a store.
    assert(
      Upgrades.magneto.evm.schedule.netStorageNoop == BigInt(100) &&
        Upgrades.magneto.evm.schedule.netStorageDirty == BigInt(100) &&
        Upgrades.magneto.evm.schedule.netStorageClean == BigInt(2900) &&
        Upgrades.magneto.evm.schedule.refundNetStorageResetFromZero == BigInt(19900) &&
        Upgrades.magneto.evm.schedule.refundNetStorageReset == BigInt(2800),
      "a derivation that reads SLOAD_GAS was not re-run against this document's terms"
    )

  it should "let a block carry the declaring transaction format" in
    // THE FIRST TIME THIS NETWORK'S ADMISSION FACET MOVES. Every rule set from
    // `frontier` to `thanos` admits the untagged format alone, set once at
    // genesis and moved by nothing, so a component built from the
    // machine-scoped constructor would apply EIP-2930's prices and admit
    // nothing -- which no case reading only the machine would catch.
    assert(
      Upgrades.magneto.admission.admittedTypes == Set(TransactionType.Legacy, TransactionType.AccessList),
      "the upgrade that adopts EIP-2930 does not admit the format it defines"
    )

  it should "have carried only the untagged format at the upgrade before it" in
    assert(
      Upgrades.thanos.admission.admittedTypes == Set(TransactionType.Legacy),
      "an upgrade below the one adopting EIP-2930 already carried its format"
    )

  it should "charge modular exponentiation under this document's own scheme" in
    // The entry rather than the record: a precompile's price is copied into its
    // entry when the entry is built, so an upgrade stating 3 and charging 20 is
    // what reading only the schedule would miss.
    assert(
      Upgrades.magneto.evm.precompiles
        .at(PrecompileSet.ModExp)
        .contains(Precompile.ModExp(BigInt(3), BigInt(200), Precompile.ModExpComplexity.SquaredWordCount)),
      "the upgrade that adopts EIP-2565 charges the entry it inherited"
    )

  it should "still size an epoch by the calibration adopted below it" in
    // THE CASE THIS NETWORK NEEDS AND THE OTHER ONE HAS NO ANALOGUE FOR. None of
    // Magneto's four documents mentions an epoch, so this value can only arrive
    // by having composed from the upgrade that set it. A composition built from
    // `phoenix` instead -- which is what the upstream counterpart composes from,
    // and what a reading taken from that network would produce here -- carries
    // an empty field, sizes every epoch above 11,700,000 by the shorter length,
    // and is refutable by nothing else in this build: the value reaches a seal
    // rather than a state root.
    assert(
      Upgrades.magneto.consensus.ecip1099Activation.contains(BigInt(11700000)),
      "this composition was built from an upgrade below the one that calibrated the epoch"
    )

  it should "write the machine's rules and admission's, and no other facet" in
    // Three of the four are confined to the machine and the fourth also reaches
    // admission, so consensus and settlement survive as the SAME values rather
    // than as equal copies. Reference equality is what distinguishes those.
    assert(
      (Upgrades.magneto.consensus eq Upgrades.thanos.consensus) &&
        (Upgrades.magneto.execution eq Upgrades.thanos.execution),
      "an upgrade whose components name the machine and admission rebuilt a third facet"
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

  "the composition this network calls Mystique" should
    "refuse to deploy code beginning with the reserved byte" in
    // The case only a composition can make: `Eip3541Spec` certifies the delta
    // and passes with the component adopted by nothing. What the machine then
    // DOES with the byte is certified where the rule is read; this is the
    // schedule's side of it, that the rule set carries the byte at all.
    assert(
      Upgrades.mystique.evm.reservedCodePrefix.contains(0xef),
      "the upgrade that adopts EIP-3541 does not reserve the byte its document names"
    )

  it should "have reserved no prefix at the upgrade before it" in
    assert(
      Upgrades.magneto.evm.reservedCodePrefix.isEmpty,
      "an upgrade below the one adopting EIP-3541 already refused the byte"
    )

  it should "cut both refunds and the bound on what a transaction hands back" in
    // Three fields by one document, and the third sits on a different facet from
    // the other two. Read as the literals that document publishes rather than
    // against the base: carrying a value forward unchanged and moving it to the
    // right number are different claims, and only the second is this upgrade's.
    assert(
      Upgrades.mystique.evm.schedule.refundNetStorageClear == BigInt(4800) &&
        Upgrades.mystique.evm.schedule.refundSelfDestruct == BigInt(0) &&
        Upgrades.mystique.execution.maxRefundQuotient == BigInt(5),
      "the upgrade that adopts EIP-3529 carries a figure other than that document's"
    )

  it should "have carried all three at their earlier values below it" in
    // The negative control for the three above, stated as three clauses rather
    // than one: a delta moving one field and missing the others would satisfy
    // any check that read only the field it moved.
    assert(
      Upgrades.magneto.evm.schedule.refundNetStorageClear == BigInt(15000) &&
        Upgrades.magneto.evm.schedule.refundSelfDestruct == BigInt(24000) &&
        Upgrades.magneto.execution.maxRefundQuotient == BigInt(2),
      "a figure EIP-3529 moves had already moved at the upgrade below the one adopting it"
    )

  it should "leave the legacy clearing refund alone, which is a field this network never spends" in
    // The two fields hold the same 15,000 and only one of them is this
    // document's. The legacy one belongs to the metering scheme this network
    // left at `phoenix`, so a delta writing it instead would put the right
    // number in a field nothing reads -- and every fixture would go on agreeing.
    // The second clause is what makes the first a statement about an unspent
    // field rather than about an unused one.
    assert(
      Upgrades.mystique.evm.schedule.refundStorageClear == Upgrades.genesisPrices.refundStorageClear &&
        Upgrades.mystique.evm.storageMetering == StorageMetering.NetWithSentry,
      "the delta reached the legacy metering field, or this network is still metering storage that way"
    )

  it should "write the machine's rules and settlement's, and no other facet" in
    // Both components are confined to the machine and one also reaches
    // settlement, so admission, consensus and the header facet survive as the
    // SAME values rather than as equal copies -- which reference equality is
    // what distinguishes. The last two clauses are the other direction: a
    // component that reached nothing would satisfy the first three alone.
    assert(
      (Upgrades.mystique.admission eq Upgrades.magneto.admission) &&
        (Upgrades.mystique.consensus eq Upgrades.magneto.consensus) &&
        (Upgrades.mystique.header eq Upgrades.magneto.header) &&
        (Upgrades.mystique.evm ne Upgrades.magneto.evm) &&
        (Upgrades.mystique.execution ne Upgrades.magneto.execution),
      "this upgrade rebuilt a facet its components do not name, or failed to reach one they do"
    )

  it should "decline all three proposals its own document omits, and be moved by each if it did not" in
    // The rules' side of a decline, in the shape the Constantinople pair above
    // uses: the second clause of each pair is what makes it an assertion rather
    // than a coincidence, because without it a component whose delta did nothing
    // would satisfy the first.
    //
    // All three components are built and adoptable, so this is a choice the
    // composition makes rather than a gap in the vocabulary. Each lands on a
    // different facet, which is why no one of them stands for the other two: the
    // fee market reaches admission and the header, the operation that reads it
    // reaches the machine, and the bomb delay reaches consensus.
    assert(
      !Upgrades.mystique.admission.admittedTypes.contains(TransactionType.DynamicFee) &&
        Upgrades.mystique
          .adopting(Eip1559.component)
          .admission
          .admittedTypes
          .contains(TransactionType.DynamicFee) &&
        Upgrades.mystique.header.feeMarket.isEmpty &&
        Upgrades.mystique.adopting(Eip1559.component).header.feeMarket.isDefined &&
        !Upgrades.mystique.evm.table.contains(Opcode.BaseFee) &&
        Upgrades.mystique.adopting(Eip3198.component).evm.table.contains(Opcode.BaseFee) &&
        Upgrades.mystique.consensus.difficultyBombDelay == BigInt(0) &&
        Upgrades.mystique.adopting(Eip3554.component).consensus.difficultyBombDelay == BigInt(9700000),
      "a proposal this upgrade's document omits is in force at it, or would not have changed it"
    )

  it should "still size an epoch by the calibration adopted two upgrades below it" in
    // The case `magneto` carries, one upgrade further from its source. Neither of
    // this upgrade's two documents mentions an epoch, so the value can only
    // arrive by having composed from the upgrade that set it -- and it reaches a
    // seal rather than a state root, so no certification tier in this build
    // could report it absent.
    assert(
      Upgrades.mystique.consensus.ecip1099Activation.contains(BigInt(11700000)),
      "this composition was built from an upgrade below the one that calibrated the epoch"
    )

  it should "reach the same rules whichever order its two components run in" in {
    // The scaladoc's disjoint-fields claim, executed rather than restated. Two
    // deltas touching one field compose to whichever ran last, so a composition
    // that commutes is evidence they name none in common -- over this base,
    // which is the only base this network composes them over.
    //
    // Facet by facet rather than on the whole value: `adopting` rebuilds the
    // component record from the order it was passed, so the two records differ
    // by construction and a whole-value comparison would fail for a reason that
    // is not about the rules.
    //
    // THE LAST CLAUSE IS THE CALIBRATION, and without it this case is satisfied
    // by a comparison that can never report a difference. EIP-2200 and EIP-2929
    // both write `netStorageNoop`, `netStorageDirty`,
    // `refundNetStorageResetFromZero` and `refundNetStorageReset`, so they are a
    // pair that must NOT commute -- read over the same base and the same facet
    // as the clauses above, which is what makes it a control for them rather
    // than a separate fact.
    val reversed = Upgrades.magneto.adopting(Eip3541.component, Eip3529.component)
    val order = Upgrades.magneto.adopting(Eip2200.component, Eip2929.component).evm
    val reverseOrder = Upgrades.magneto.adopting(Eip2929.component, Eip2200.component).evm
    assert(
      reversed.evm == Upgrades.mystique.evm &&
        reversed.execution == Upgrades.mystique.execution &&
        reversed.admission == Upgrades.mystique.admission &&
        reversed.consensus == Upgrades.mystique.consensus &&
        reversed.header == Upgrades.mystique.header &&
        order != reverseOrder,
      "the two components do not commute, or the comparison that says they do cannot see an order that matters"
    )
  }

  "the composition this network calls Spiral" should "start the beneficiary warm" in
    // The schedule's side of EIP-3651: `Eip3651Spec` certifies the delta and
    // passes with the component adopted by nothing. This is that the rule set
    // carries it at all.
    assert(
      Upgrades.spiral.evm.coinbaseStartsWarm,
      "the upgrade that adopts EIP-3651 charges cold access for the address every block already names"
    )

  it should "have started it cold at the upgrade before it" in
    assert(
      !Upgrades.mystique.evm.coinbaseStartsWarm,
      "an upgrade below the one adopting EIP-3651 already warmed the beneficiary"
    )

  it should "carry PUSH0 at the cheapest tier" in
    // Read as the literal that document publishes rather than against the base:
    // an operation added at the wrong price is present and wrong, which is the
    // failure a presence check alone cannot see.
    assert(
      settledCost(Upgrades.spiral.evm.table, Opcode.Push0).contains(BigInt(2)),
      "the upgrade that adopts EIP-3855 lacks the operation or charges other than the base tier"
    )

  it should "have had no operation at that code below it" in
    assert(
      !Upgrades.mystique.evm.table.contains(Opcode.Push0),
      "an upgrade below the one adopting EIP-3855 already answered at that code"
    )

  it should "bound initcode at twice the deployed-code bound, and meter it" in
    // Two fields by one document. The bound is stated as the literal ECIP-1109's
    // own summary of EIP-3860 publishes -- 49,152 -- rather than as the
    // derivation that produces it, because a derivation restated is not a second
    // reading of it. The second clause pins the base the derivation runs over,
    // which is what makes the first a figure rather than a coincidence.
    assert(
      Upgrades.spiral.evm.maxInitcodeSize.contains(49152) &&
        Upgrades.spiral.evm.maxCodeSize.contains(24576) &&
        Upgrades.spiral.evm.schedule.initcodePerWord == BigInt(2),
      "the upgrade that adopts EIP-3860 carries a bound or a rate other than that document's"
    )

  it should "have bounded neither at the upgrade before it" in
    // The negative control for both, and the deployed-code bound is deliberately
    // NOT in it: that one is `atlantis`'s and is unchanged here, so a clause
    // asserting it absent below would be false.
    assert(
      Upgrades.mystique.evm.maxInitcodeSize.isEmpty &&
        Upgrades.mystique.evm.schedule.initcodePerWord == BigInt(0),
      "an upgrade below the one adopting EIP-3860 already bounded or metered initcode"
    )

  it should "still report the block's own difficulty at 0x44" in
    // The machine half of ECIP-1109's first omission, and the reason it is an
    // omission rather than a withheld operation: the code answers here as it
    // answered below, and what EIP-4399 would have changed is the quantity it
    // reports. Three clauses -- the operation present, the quantity unchanged,
    // and the delta that would change it -- because the first two alone are
    // satisfied by a rule set on which the field cannot move at all.
    assert(
      Upgrades.spiral.evm.table.contains(Opcode.Difficulty) &&
        Upgrades.spiral.evm.blockRandomness == BlockRandomness.Unavailable &&
        Upgrades.spiral.adopting(Eip4399.component).evm.blockRandomness == BlockRandomness.Eip4399,
      "this network supplanted the quantity 0x44 reports, or the field it reports from cannot move"
    )

  it should "commit to no withdrawals list in its header" in
    // The header half of the second omission, in the same three-clause shape:
    // a network granting rewards only to miners has no validator exits to
    // credit, so the field stays absent, and the adopting clause is what proves
    // the absence is this composition's rather than the field's.
    assert(
      !Upgrades.spiral.header.carriesWithdrawalsRoot &&
        Upgrades.spiral.adopting(Eip4895.component).header.carriesWithdrawalsRoot,
      "this network committed to a withdrawals list, or the field cannot be set at all"
    )

  it should "write the machine's rules and no other facet" in
    // All three components are machine-scoped, so every other facet survives as
    // the SAME value rather than as an equal copy -- which reference equality is
    // what distinguishes. The last clause is the calibration: without it the
    // case is satisfied by a composition that changed nothing anywhere.
    assert(
      (Upgrades.spiral.admission eq Upgrades.mystique.admission) &&
        (Upgrades.spiral.consensus eq Upgrades.mystique.consensus) &&
        (Upgrades.spiral.header eq Upgrades.mystique.header) &&
        (Upgrades.spiral.execution eq Upgrades.mystique.execution) &&
        (Upgrades.spiral.evm ne Upgrades.mystique.evm),
      "a component reached past the machine, or none of them reached it"
    )

  it should "still size an epoch by the calibration adopted three upgrades below it" in
    // The case `mystique` carries, one upgrade further from its source. None of
    // this upgrade's three documents mentions an epoch, so the value can only
    // arrive by having composed from the upgrade that set it -- and it reaches a
    // seal rather than a state root, so no certification tier in this build
    // could report it absent.
    assert(
      Upgrades.spiral.consensus.ecip1099Activation.contains(BigInt(11700000)),
      "this composition was built from an upgrade below the one that calibrated the epoch"
    )

  it should "reach the same rules whichever order its three components run in" in {
    // The scaladoc's disjoint-fields claim, executed rather than restated, over
    // the reversal rather than over all six permutations: reversing is what
    // exposes an order dependency between any pair, since every pair's relative
    // order is inverted by it.
    //
    // Facet by facet rather than on the whole value: `adopting` rebuilds the
    // component record from the order it was passed, so the two records differ
    // by construction and a whole-value comparison would fail for a reason that
    // is not about the rules.
    //
    // THE LAST CLAUSE IS THE CALIBRATION, and without it this case is satisfied
    // by a comparison that can never report a difference. EIP-2200 and EIP-2929
    // both write `netStorageNoop`, `netStorageDirty`,
    // `refundNetStorageResetFromZero` and `refundNetStorageReset`, so they are a
    // pair that must NOT commute -- read over the same facet as the clauses
    // above, which is what makes it a control for them.
    val reversed = Upgrades.mystique.adopting(Eip3860.component, Eip3855.component, Eip3651.component)
    val order = Upgrades.magneto.adopting(Eip2200.component, Eip2929.component).evm
    val reverseOrder = Upgrades.magneto.adopting(Eip2929.component, Eip2200.component).evm
    assert(
      reversed.evm == Upgrades.spiral.evm &&
        reversed.execution == Upgrades.spiral.execution &&
        reversed.admission == Upgrades.spiral.admission &&
        reversed.consensus == Upgrades.spiral.consensus &&
        reversed.header == Upgrades.spiral.header &&
        order != reverseOrder,
      "the three components do not commute, or the comparison that says they do cannot see an order that matters"
    )
  }
