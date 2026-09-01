package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.evm.{Cost, Opcode, Operation, StateAccessMetering, StorageMetering}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2929 changes: three prices that did not exist, five that
  * did, one rule, and four table entries that stop holding a figure.
  *
  * ==Through [[Eip2929.component]], because the wiring is what is untested==
  *
  * The three deltas are reachable on their own, so a spec calling one directly
  * passes with the component wired to nothing.
  *
  * ==Every figure is a literal here, and the five moved ones are literals for a
  * second reason==
  *
  * A case reading a figure off the delta agrees with the delta however wrong
  * both are. The five moved ones are additionally the OUTPUT of derivations the
  * document states as expressions -- `SSTORE_SET_GAS - SLOAD_GAS` and the rest
  * -- so writing the expression here would reproduce whatever the delta got
  * wrong about its terms. The literals are worked out from the document's own
  * table by hand.
  */
class Eip2929Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.muirGlacier

  private val adopted: UpgradeRules = base.adopting(Eip2929.component)

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(rules: UpgradeRules, opcode: Opcode): Option[BigInt] =
    rules.evm.table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  /** The four operations whose entries stop holding a figure. */
  private val rebuilt = Vector(Opcode.Balance, Opcode.ExtCodeSize, Opcode.ExtCodeHash, Opcode.SLoad)

  /** Seven of the eleven this document reaches that already worked out their own
    * price, and must go on doing so.
    */
  private val alreadyComputed =
    Vector(
      Opcode.ExtCodeCopy,
      Opcode.Call,
      Opcode.CallCode,
      Opcode.DelegateCall,
      Opcode.StaticCall,
      Opcode.SelfDestruct,
      Opcode.SStore
    )

  "adopting EIP-2929" should "price a repeat reach at one hundred" in
    assert(adopted.evm.schedule.warmAccess == BigInt(100), "WARM_STORAGE_READ_COST is 100")

  it should "price a first reach at an account at two thousand six hundred" in
    assert(adopted.evm.schedule.coldAccountAccess == BigInt(2600), "COLD_ACCOUNT_ACCESS_COST is 2600")

  it should "price a first reach at a slot at two thousand one hundred" in
    assert(adopted.evm.schedule.coldStorageAccess == BigInt(2100), "COLD_SLOAD_COST is 2100")

  it should "have priced none of the three before it was adopted" in
    // Not an emptiness check on one of them: a delta that moved the wrong member
    // would leave the one it should have moved at zero and pass that.
    assert(
      base.evm.schedule.warmAccess == BigInt(0) &&
        base.evm.schedule.coldAccountAccess == BigInt(0) &&
        base.evm.schedule.coldStorageAccess == BigInt(0),
      "a network below this document states no such price, and zero is how that is written"
    )

  it should "put the storage scheme's no-op and dirty clauses at the repeat-reach price" in
    // SLOAD_GAS becomes WARM_STORAGE_READ_COST. Two fields, because this
    // schedule splits that one variable, and a delta moving one leaves the two
    // clauses of one scheme disagreeing.
    assert(
      adopted.evm.schedule.netStorageNoop == BigInt(100) && adopted.evm.schedule.netStorageDirty == BigInt(100),
      "both clauses EIP-2200 prices from SLOAD_GAS move together"
    )

  it should "have held those two at eight hundred before it was adopted" in
    assert(
      base.evm.schedule.netStorageNoop == BigInt(800) && base.evm.schedule.netStorageDirty == BigInt(800),
      "EIP-2200's figure is what this document moves"
    )

  it should "cut the reset charge by exactly the first-reach price" in
    // SSTORE_RESET_GAS becomes 5000 - COLD_SLOAD_COST. The document's stated
    // reason is that a store now also pays the first-reach price, so the total
    // is unchanged -- which is why the two figures must be checked together and
    // not each against a memory of what it was.
    assert(
      adopted.evm.schedule.netStorageClean == BigInt(2900) &&
        base.evm.schedule.netStorageClean - adopted.evm.schedule.coldStorageAccess ==
        adopted.evm.schedule.netStorageClean,
      "the reduction is 5000 - 2100 and its whole purpose is that the total does not move"
    )

  it should "RAISE the refund for a slot restored to empty" in
    // The one of the five that goes UP, and the one a reader sweeping for
    // reductions will not expect: SSTORE_SET_GAS - SLOAD_GAS is 20000 - 100
    // where it was 20000 - 800.
    assert(
      adopted.evm.schedule.refundNetStorageResetFromZero == BigInt(19900) &&
        adopted.evm.schedule.refundNetStorageResetFromZero > base.evm.schedule.refundNetStorageResetFromZero,
      "a derivation whose subtrahend fell gives a larger difference"
    )

  it should "cut the refund for a slot restored to what it held" in
    // SSTORE_RESET_GAS - SLOAD_GAS, both terms having moved: 2900 - 100.
    assert(
      adopted.evm.schedule.refundNetStorageReset == BigInt(2800),
      "the derivation re-runs against both new terms, not against one"
    )

  it should "leave what setting an empty slot costs exactly where it found it" in
    // SSTORE_SET_GAS is not in the document's table. It is the term the raised
    // refund is derived FROM, so a delta that moved it would keep that refund
    // arithmetically consistent and be wrong.
    assert(
      adopted.evm.schedule.netStorageInit == base.evm.schedule.netStorageInit &&
        adopted.evm.schedule.netStorageInit == BigInt(20000),
      "STORAGE_SET is not this document's to move"
    )

  it should "leave the refund for clearing a slot exactly where it found it" in
    // EIP-3529 moves that one, at a later upgrade.
    assert(
      adopted.evm.schedule.refundNetStorageClear == base.evm.schedule.refundNetStorageClear,
      "a document from a later upgrade's membership was applied here"
    )

  it should "leave the SLOAD operation's settled price and the legacy reset price where it found them" in
    // Neither is in the document's table. The first is REPLACED rather than
    // repriced -- that operation works out its own charge from here on, so the
    // field is read by nothing -- and the second belongs to a storage scheme
    // this network stopped running two documents ago. Writing a figure into
    // either would be stating a price no source states.
    assert(
      adopted.evm.schedule.storageLoad == base.evm.schedule.storageLoad &&
        adopted.evm.schedule.storageReset == base.evm.schedule.storageReset,
      "a field the document does not name was given a value anyway"
    )

  it should "settle that reaching state is priced by whether it has been reached" in
    assert(
      adopted.evm.stateAccessMetering == StateAccessMetering.WarmCold,
      "the rule is what the eleven affected operations read when they spend"
    )

  it should "have priced every reach alike before it was adopted" in
    assert(
      base.evm.stateAccessMetering == StateAccessMetering.Settled,
      "the earlier scheme is what this document replaces"
    )

  it should "leave the four repriced entries working out their own price" in
    assert(
      rebuilt.forall(opcode => settledCost(adopted, opcode).isEmpty),
      "an entry still holding a figure charges that figure, whatever the rule says"
    )

  it should "have settled a price for all four before it was adopted" in
    // The control the case above needs. Without it, an entry this document never
    // reached would satisfy that case by having had no figure to begin with.
    assert(
      rebuilt.forall(opcode => settledCost(base, opcode).isDefined),
      "an entry that was already computed proves nothing about this delta"
    )

  it should "leave the seven that already worked out their own price alone" in
    assert(
      alreadyComputed.forall(opcode => settledCost(adopted, opcode).isEmpty) &&
        alreadyComputed.forall(opcode => settledCost(base, opcode).isEmpty),
      "an operation that computed its own charge was given a settled one"
    )

  it should "leave every other entry in the table exactly where it found it" in
    // The four are named; nothing else may move. A delta rebuilding a fifth
    // entry would pass every case above.
    assert(
      Opcode.values.filterNot(rebuilt.contains).forall { opcode =>
        adopted.evm.table.operationAt(opcode.code) == base.evm.table.operationAt(opcode.code)
      },
      "a repricing reached an operation this document does not name"
    )

  it should "leave the storage scheme itself at three cases" in
    // The document moves five of EIP-2200's parameters and none of its clauses:
    // "The other parameters defined in EIP 2200 are unchanged." A fourth case
    // here would be a scheme no source describes.
    assert(
      adopted.evm.storageMetering == StorageMetering.NetWithSentry &&
        adopted.evm.storageMetering == base.evm.storageMetering,
      "the metering scheme is not this document's to change"
    )

  it should "leave the stipend the sentry compares against where it found it" in
    assert(
      adopted.evm.schedule.callStipend == base.evm.schedule.callStipend,
      "the refusal threshold is EIP-2200's and this document does not touch it"
    )

  it should "leave the precompile set untouched" in
    assert(adopted.evm.precompiles eq base.evm.precompiles, "a repricing of operations reached a native")

  it should "leave every facet but the machine's untouched" in
    assert(
      (adopted.consensus eq base.consensus) && (adopted.admission eq base.admission) &&
        (adopted.execution eq base.execution),
      "a document confined to the machine wrote another facet"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(2929)), "the journal must record what was adopted")
