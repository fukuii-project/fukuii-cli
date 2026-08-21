package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.Ethereum
import org.fukuii.evm.{ChainRules, Cost, Opcode, OpcodeTable, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-150 actually changes, and what it must leave alone.
  *
  * ==Here rather than with the machine, because these are the proposal's claims==
  *
  * Every figure below is the document's, read from its own text. The machine
  * knows how to apply a delta and knows nothing about which delta is right;
  * asserting both in one place would leave a failure unable to say which of the
  * two was wrong.
  */
class Eip150Spec extends AnyFlatSpec:

  private val base: ChainRules = Ethereum.homestead.evm

  private val repriced: ChainRules = base.applying(Eip150.stateReadRepricing)

  /** What a table charges for `opcode` before it runs, where that is settled. */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  "stateReadRepricing" should "move every price it names" in
    assert(
      repriced.schedule.storageLoad == BigInt(200) && repriced.schedule.balance == BigInt(400) &&
        repriced.schedule.externalBase == BigInt(700) && repriced.schedule.callBase == BigInt(700),
      "a price the proposal names did not move"
    )

  it should "reprice the table entry of every operation priced from the schedule when the table was built" in
    // The half of this delta that is invisible from the schedule. Three of the
    // four fields were copied into a table entry when the original instruction
    // set was built, so a proposal that moved the schedule alone would leave
    // these three charging what they charged before with nothing in the schedule
    // to show it.
    assert(
      settledCost(repriced.table, Opcode.SLoad).contains(BigInt(200)) &&
        settledCost(repriced.table, Opcode.Balance).contains(BigInt(400)) &&
        settledCost(repriced.table, Opcode.ExtCodeSize).contains(BigInt(700)),
      "an operation charged through the table kept its earlier price"
    )

  it should "leave every entry it does not name exactly as it found it" in {
    // Stated as the set that moved rather than as spot checks, so an entry
    // reached by accident fails as loudly as one missed on purpose.
    val named = Set(Opcode.SLoad, Opcode.Balance, Opcode.ExtCodeSize)
    val moved =
      Opcode.values.toSet.filter(opcode =>
        repriced.table.operationAt(opcode.code) != base.table.operationAt(opcode.code)
      )
    assert(moved == named, s"the entries that moved were ${moved.toString} rather than ${named.toString}")
  }

  it should "leave the precompile prices as the same value, not an equal copy" in
    // The precompiles are built from the schedule too, so a proposal that
    // rebuilt them from the repriced one would look correct and would have
    // rewritten what it does not name.
    assert(repriced.precompiles eq base.precompiles, "a repricing of operations rebuilt the precompile set")

  "forwardedGasCap" should "leave the table, the schedule and the precompiles as the same values" in {
    val changed = base.applying(Eip150.forwardedGasCap)
    assert(
      (changed.table eq base.table) && (changed.schedule eq base.schedule) &&
        (changed.precompiles eq base.precompiles),
      "a rule about forwarding rebuilt something it does not name"
    )
  }

  "selfDestructCharge" should "leave the refund exactly where it found it" in
    // The proposal that charges for this operation does not touch what it earns
    // back, and a delta that moved both would be wrong on every network from
    // this fork onward. Pinned because the two sit beside each other and the
    // specification's own diff at this fork touches the refund's line without
    // changing its value.
    assert(
      base.applying(Eip150.selfDestructCharge).schedule.refundSelfDestruct == base.schedule.refundSelfDestruct,
      "a charge for ending an invocation moved the refund for it"
    )

  it should "leave the table as the same value, since a conditional charge is not a table entry" in
    assert(
      base.applying(Eip150.selfDestructCharge).table eq base.table,
      "the operation works out its own price, so repricing it is a change to the schedule alone"
    )
