package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, Opcode, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1052 changes, and the one assertion the rest of this file
  * exists to protect.
  *
  * ==THE LITERAL 400 IS THE POINT OF THIS SPEC==
  *
  * Every other assertion here would pass with `EXTCODEHASH` priced from
  * `externalBase`, which is 700 at this fork. The byte would be right, the
  * entry count would be right, the whole-record comparison would be right
  * because it is built from the same wrong expression, and no certification
  * corpus this build reads reaches Constantinople at all. **A test naming the
  * figure is the only thing between this project and a silent 300-gas
  * overcharge on every `EXTCODEHASH` ever executed.**
  *
  * The confusion is not careless. `EXTCODESIZE` and `EXTCODECOPY` are the other
  * two operations that reach another account's code, this project prices both
  * from `externalBase`, and this is the third of that family -- so the wrong
  * answer is the one a reader arrives at by understanding the code rather than
  * by ignoring it.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * What the operation DOES -- and in particular that an EMPTY account answers
  * zero rather than the hash of empty code -- is the machine's, and
  * `org.fukuii.evm.InterpreterSpec` certifies it there.
  */
class Eip1052Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.byzantium

  private val adopted: UpgradeRules = base.adopting(Eip1052.component)

  "EIP-1052" should "not already be in the table it adds to" in
    assert(base.evm.table.operationAt(0x3f).isEmpty, "the preceding table already ran an operation at this byte")

  it should "place EXTCODEHASH at 0x3f" in
    assert(adopted.evm.table.operationAt(0x3f).map(_.opcode).contains(Opcode.ExtCodeHash), "the document names 0x3f")

  it should "price it at exactly 400" in
    // "The gas cost of the `EXTCODEHASH` is 400", EIPS/eip-1052.md. Asserted as
    // the literal the document publishes and NOT as a schedule member, because
    // every candidate member is the mistake this is guarding against.
    assert(
      adopted.evm.table.operationAt(0x3f).map(_.cost).contains(Cost.Fixed(BigInt(400))),
      "EXTCODEHASH must cost the 400 its document states"
    )

  it should "NOT be priced from externalBase, which is 700 here" in
    // The named negative. Stated separately from the assertion above so that a
    // failure says which mistake was made rather than only that a number moved.
    assert(
      base.evm.schedule.externalBase == BigInt(700) &&
        !adopted.evm.table.operationAt(0x3f).map(_.cost).contains(Cost.Fixed(base.evm.schedule.externalBase)),
      "EXTCODEHASH was priced from the tier its two sibling operations use, which overcharges by 300"
    )

  it should "carry the figure on a member of its own" in
    // So that EIP-1884's later 400 -> 700 repricing has somewhere to land. A
    // literal in the delta would make that repricing unexpressible.
    assert(
      base.evm.schedule.extCodeHash == BigInt(400),
      "the schedule must state this network's figure for the operation"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 1,
      "adopting a document that introduces one operation moved the table by some other amount"
    )

  it should "settle that entry and nothing else in the machine" in
    assert(
      adopted.evm == base.evm
        .copy(table = base.evm.table.adding(Operation(Opcode.ExtCodeHash, Cost.Fixed(base.evm.schedule.extCodeHash)))),
      "the adopting rules differ from the earlier ones by something other than the entry"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "the document reprices nothing, so it must not rebuild the record"
    )

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(org.fukuii.chainspec.ProposalId.Eip(1052)),
      "the journal must record what was adopted"
    )
