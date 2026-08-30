package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, Opcode, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-145 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip145.component]], because the wiring is what is untested==
  *
  * The three deltas are reachable on their own and a spec calling them directly
  * passes with the component wired to nothing.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The bytes and the tier below are read from the proposal's own text. What the
  * operations DO -- and in particular how each of the three answers a shift at
  * or beyond the width, which is where they stop agreeing --
  * is the machine's, and `org.fukuii.evm.WordSpec` certifies it there.
  */
class Eip145Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.byzantium

  private val adopted: UpgradeRules = base.adopting(Eip145.component)

  "EIP-145" should "not already be in the table it adds to" in
    // Without this the deltas would be unobservable and the seam would have
    // proved nothing -- the same reason `OpcodeTable.laterThanOriginal` exists.
    assert(
      base.evm.table.operationAt(0x1b).isEmpty &&
        base.evm.table.operationAt(0x1c).isEmpty &&
        base.evm.table.operationAt(0x1d).isEmpty,
      "the preceding table already ran an operation at one of these bytes"
    )

  it should "place SHL at 0x1b" in
    assert(adopted.evm.table.operationAt(0x1b).map(_.opcode).contains(Opcode.Shl), "the document names 0x1b")

  it should "place SHR at 0x1c" in
    assert(adopted.evm.table.operationAt(0x1c).map(_.opcode).contains(Opcode.Shr), "the document names 0x1c")

  it should "place SAR at 0x1d" in
    assert(adopted.evm.table.operationAt(0x1d).map(_.opcode).contains(Opcode.Sar), "the document names 0x1d")

  it should "price all three at the verylow tier this network already sets" in
    // The document introduces no figure of its own -- "of the `verylow` tier" --
    // so the assertion is against the schedule's own member rather than against
    // a literal. A literal here would be a second copy of a value the schedule
    // holds, and would keep passing if the tier moved.
    assert(
      List(0x1b, 0x1c, 0x1d).forall(byte =>
        adopted.evm.table.operationAt(byte).map(_.cost).contains(Cost.Fixed(base.evm.schedule.veryLow))
      ),
      "one of the three was priced apart from the tier the document names"
    )

  it should "add exactly three entries" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 3,
      "adopting a document that introduces three operations moved the table by some other amount"
    )

  it should "settle those three entries and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member reached
    // by accident fails as loudly as a named one failing to move.
    assert(
      adopted.evm == base.evm.copy(table =
        base.evm.table
          .adding(Operation(Opcode.Shl, Cost.Fixed(base.evm.schedule.veryLow)))
          .adding(Operation(Opcode.Shr, Cost.Fixed(base.evm.schedule.veryLow)))
          .adding(Operation(Opcode.Sar, Cost.Fixed(base.evm.schedule.veryLow)))
      ),
      "the adopting rules differ from the earlier ones by something other than the three entries"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // The document names no figure, so it reaches the schedule nowhere.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a delta that rebuilt the schedule would be indistinguishable by value"
    )

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(org.fukuii.chainspec.ProposalId.Eip(145)),
      "the journal must record what was adopted"
    )
