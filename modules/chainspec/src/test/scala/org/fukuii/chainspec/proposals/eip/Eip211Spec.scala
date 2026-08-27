package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, Opcode, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-211 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip211.component]], because the wiring is what is untested==
  *
  * The deltas are reachable on their own and a spec calling them directly
  * passes with the component wired to nothing. What a network adopts is the
  * component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The two bytes and the two prices below are read from the proposal's own
  * text. What the operations DO -- the buffer's lifetime, the refusal to read
  * past its end -- is the machine's, and `org.fukuii.evm.InterpreterSpec` and
  * `org.fukuii.evm.InvocationSpec` certify it there.
  */
class Eip211Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip211.component)

  "adopting EIP-211" should "put an operation at 0x3d priced at the base tier" in
    // "RETURNDATASIZE: 0x3d ... Gas costs: 2 (same as CALLDATASIZE)",
    // ethereum/EIPs @ 9e393a79, EIPS/eip-211.md, Final. The tier is named
    // rather than the figure, because the document names the operation it
    // shares a price with and not a number of its own.
    assert(
      adopted.evm.table
        .operationAt(0x3d)
        .contains(
          Operation(Opcode.ReturnDataSize, Cost.Fixed(base.evm.schedule.base))
        ),
      "0x3d runs something other than the operation this document introduces, at some other price"
    )

  it should "price it at whatever CALLDATASIZE is priced at" in
    // The document states the price as an equality with another operation, so
    // the assertion is that equality rather than the figure it currently
    // resolves to. A schedule moving the tier moves both, and a figure written
    // here would pass for a build that had pinned one of them.
    assert(
      adopted.evm.table.operationAt(0x3d).map(_.cost) == adopted.evm.table.operationAt(0x36).map(_.cost),
      "the operation the document prices by naming CALLDATASIZE is priced apart from it"
    )

  it should "put an operation at 0x3e priced from its operands" in
    // "RETURNDATACOPY: 0x3e ... Gas costs: 3 + 3 * ceil(amount / 32) (same as
    // CALLDATACOPY)". The amount is an operand, so no entry can settle it.
    assert(
      adopted.evm.table.operationAt(0x3e).contains(Operation(Opcode.ReturnDataCopy, Cost.Computed)),
      "0x3e runs something other than the operation this document introduces, at some other price"
    )

  it should "have run nothing at either byte before it was adopted" in
    // The control. Without it the two cases above pass against a table that
    // already carried both entries, and an absent entry is what every height
    // before this document runs.
    assert(
      base.evm.table.operationAt(0x3d).isEmpty && base.evm.table.operationAt(0x3e).isEmpty,
      "the preceding table already ran an operation at one of these bytes"
    )

  it should "add exactly two entries" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 2,
      "adopting a document that introduces two operations moved the table by some other amount"
    )

  it should "settle those entries and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member reached
    // by accident fails as loudly as either named one failing to move.
    assert(
      adopted.evm == base.evm.copy(table =
        base.evm.table
          .adding(Operation(Opcode.ReturnDataSize, Cost.Fixed(base.evm.schedule.base)))
          .adding(Operation(Opcode.ReturnDataCopy, Cost.Computed))
      ),
      "the adopting rules differ from the earlier ones by something other than the two entries"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // Both prices are ones the schedule already holds -- a tier for the first,
    // the copying family's terms for the second -- so this document adds no
    // member. A delta that rebuilt the record would be indistinguishable by
    // value from one that did not, which is why the claim is identity.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document whose prices are ones the schedule already holds rebuilt it"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "two operations added to the machine altered a facet the document does not name"
    )

  it should "leave the copying operations it is priced alongside where it found them" in
    // The three entries a reader is most likely to conflate the second one
    // with. The document prices it by naming CALLDATACOPY; it does not move
    // any of them.
    assert(
      adopted.evm.table.operationAt(0x37) == base.evm.table.operationAt(0x37) &&
        adopted.evm.table.operationAt(0x39) == base.evm.table.operationAt(0x39) &&
        adopted.evm.table.operationAt(0x3c) == base.evm.table.operationAt(0x3c),
      "adopting a copying operation moved one of the three it shares a price with"
    )
