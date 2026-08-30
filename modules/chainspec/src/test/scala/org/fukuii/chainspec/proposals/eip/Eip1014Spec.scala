package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, Opcode, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1014 changes, and the far larger set it must leave alone.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The byte and the pricing SHAPE are read from the proposal's own text. The
  * arithmetic behind that shape -- the base, the per-word hashing term and
  * memory expansion summing to one charge -- and the address derivation are the
  * machine's, and `org.fukuii.evm.InterpreterSpec` and
  * `org.fukuii.evm.ContractAddressSpec` certify them there.
  */
class Eip1014Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.byzantium

  private val adopted: UpgradeRules = base.adopting(Eip1014.component)

  "EIP-1014" should "not already be in the table it adds to" in
    assert(base.evm.table.operationAt(0xf5).isEmpty, "the preceding table already ran an operation at this byte")

  it should "place CREATE2 at 0xf5" in
    assert(adopted.evm.table.operationAt(0xf5).map(_.opcode).contains(Opcode.Create2), "the document names 0xf5")

  it should "price it from its operands rather than from a figure" in
    // Two of the three terms depend on an operand -- the hashing term on the
    // code's length, memory expansion on the region -- so a fixed entry here
    // would be a price standing where a computation belongs, and would be
    // indistinguishable from a checked one.
    assert(
      adopted.evm.table.operationAt(0xf5).map(_.cost).contains(Cost.Computed),
      "an operation whose charge depends on its operands was given a settled price"
    )

  it should "price it the same way CREATE is priced" in
    // Not a restatement of the assertion above: this one fails if CREATE's own
    // entry ever stops being computed, which is what would make the shared
    // implementation wrong rather than merely differently shaped.
    assert(
      adopted.evm.table.operationAt(0xf5).map(_.cost) == adopted.evm.table.operationAt(0xf0).map(_.cost),
      "the document says the cost is CREATE's plus a term, so the two must carry the same shape"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 1,
      "adopting a document that introduces one operation moved the table by some other amount"
    )

  it should "settle that entry and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(table = base.evm.table.adding(Operation(Opcode.Create2, Cost.Computed))),
      "the adopting rules differ from the earlier ones by something other than the entry"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    assert(adopted.evm.schedule eq base.evm.schedule, "the document names no figure, so it must not rebuild the record")

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(org.fukuii.chainspec.ProposalId.Eip(1014)),
      "the journal must record what was adopted"
    )
