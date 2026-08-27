package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, Opcode}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-140 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip140.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The byte and the pricing shape below are read from the proposal's own text.
  * What the operation DOES -- the gas it keeps, the payload it hands back, the
  * writes it drops -- is the machine's, and `org.fukuii.evm.InterpreterSpec`
  * and `org.fukuii.evm.InvocationSpec` certify it there.
  */
class Eip140Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip140.component)

  "adopting EIP-140" should "put an operation at 0xfd" in
    // "the REVERT instruction is introduced at 0xfd", ethereum/EIPs @ 9e393a79,
    // EIPS/eip-140.md, Final.
    assert(
      adopted.evm.table.operationAt(0xfd).contains(org.fukuii.evm.Operation(Opcode.Revert, Cost.Computed)),
      "0xfd runs something other than the operation this document introduces"
    )

  it should "have run nothing at that byte before it was adopted" in
    // The control. Without it the case above passes against a table that
    // already carried the entry, and an absent entry is what every height
    // before this document runs.
    assert(
      base.evm.table.operationAt(0xfd).isEmpty,
      "the preceding table already ran an operation at this byte"
    )

  it should "price it from its operands rather than from a figure" in
    // "the contract only has to pay for memory", which is a charge no table can
    // settle. A fixed entry here would be a price standing where a computation
    // belongs, and it would be indistinguishable from a checked one.
    assert(
      adopted.evm.table.operationAt(0xfd).map(_.cost).contains(Cost.Computed),
      "an operation whose whole charge is memory expansion was given a settled price"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 1,
      "adopting a document that introduces one operation moved the table by some other amount"
    )

  it should "settle that entry and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member reached
    // by accident fails as loudly as the named one failing to move.
    assert(
      adopted.evm == base.evm
        .copy(table = base.evm.table.adding(org.fukuii.evm.Operation(Opcode.Revert, Cost.Computed))),
      "the adopting rules differ from the earlier ones by something other than the entry"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // The document names no figure, so it reaches the schedule nowhere. A delta
    // that rebuilt it would be indistinguishable by value from one that did
    // not, which is why the claim is identity.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document that names no price rebuilt the price list"
    )

  it should "leave the precompile prices as the same value" in
    assert(
      adopted.evm.precompiles eq base.evm.precompiles,
      "a document that adds an operation rebuilt the precompile set"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "an operation added to the machine altered a facet the document does not name"
    )

  it should "leave the operation that ends an invocation normally where it found it" in
    // The entry a reader is most likely to conflate this one with: the document
    // prices the two alike and they are separate operations at separate bytes.
    assert(
      adopted.evm.table.operationAt(0xf3) == base.evm.table.operationAt(0xf3),
      "adopting the operation that abandons an invocation moved the one that ends it normally"
    )
