package org.fukuii.evm

import org.scalatest.flatspec.AnyFlatSpec

/** The fork seam's own properties, tested before a fork is expressed through it.
  *
  * A baseline with no delta over it has never been exercised as a baseline, so
  * everything the seam claims is claimed by a single instantiation. These are
  * the claims themselves, each stated as something a wrong delta would break --
  * not as a description of what the code does.
  *
  * **The load-bearing one is that a proposal leaves alone what it does not
  * name.** A delta that rewrites the baseline is the seam failing, and it fails
  * quietly: the fork it produces is still correct, and the next fork derived
  * from the same baseline is not.
  */
class ChainRulesSpec extends AnyFlatSpec:

  private val base: ChainRules = ChainRules.Baseline

  /** Changes one price and nothing else, so what survives is observable. */
  private val repricing: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionBase = BigInt(53000)))

  /** Settles one behavior and nothing else. */
  private val depositRule: Proposal = _.copy(codeDepositMustSucceed = true)

  private val secondRepricing: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionBase = BigInt(21000)))

  /** What a table charges for `opcode` before it runs, where that is settled.
    *
    * An operation that works out its own price has no number here to compare,
    * so absence and a figure are different answers rather than a missing one.
    */
  private def settledCost(table: OpcodeTable, opcode: Opcode): Option[BigInt] =
    table.operationAt(opcode.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }

  "the baseline" should "leave a creation that cannot pay for its code holding no code rather than failing" in
    // The rule the machine started with, pinned here so a fork that reverses it
    // has something to reverse. A baseline that already carried the later
    // behavior would make its delta unobservable.
    assert(!base.codeDepositMustSucceed)

  "applying" should "change nothing when given no proposal" in
    assert(base.applying() == base)

  it should "leave every field the proposal does not name as the same value, not an equal copy" in {
    // `eq`, not `==`. An equal copy would pass a value comparison and still mean
    // the seam had rebuilt what it was asked to leave alone -- which is the
    // difference between a delta and a fork of the baseline.
    val changed = base.applying(repricing)
    assert(
      (changed.table eq base.table) && (changed.precompiles eq base.precompiles),
      "a repricing rebuilt the table or the precompiles"
    )
  }

  it should "leave the rules it was applied to unaltered" in {
    // The baseline is a shared value every fork derives from, so a proposal that
    // mutated it would corrupt every other fork rather than only its own.
    val _ = base.applying(repricing, depositRule)
    assert(
      base.schedule.transactionBase == BigInt(21000) && !base.codeDepositMustSucceed,
      "applying a proposal altered the baseline it derived from"
    )
  }

  it should "carry a behavior change no table and no schedule could express" in
    assert(base.applying(depositRule).codeDepositMustSucceed)

  it should "leave the schedule alone when the proposal names only a behavior" in
    assert(base.applying(depositRule).schedule eq base.schedule)

  it should "apply proposals in the order given" in
    // Two proposals touching one price compose to whichever ran last, so the
    // order is the caller's to state rather than the seam's to sort.
    assert(base.applying(repricing, secondRepricing).schedule.transactionBase == BigInt(21000))

  it should "compose to a different result when that order is reversed" in
    assert(base.applying(secondRepricing, repricing).schedule.transactionBase == BigInt(53000))

  "stateReadRepricing" should "move every price it names" in {
    val repriced = base.applying(Proposals.stateReadRepricing).schedule
    assert(
      repriced.storageLoad == BigInt(200) && repriced.balance == BigInt(400) &&
        repriced.externalBase == BigInt(700) && repriced.callBase == BigInt(700),
      "a price the proposal names did not move"
    )
  }

  it should "reprice the table entry of every operation the baseline priced from the schedule" in {
    // The half of this delta that is invisible from the schedule. Three of the
    // four fields were copied into a table entry when the baseline was built, so
    // a proposal that moved the schedule alone would leave these three charging
    // what they charged before with nothing in the schedule to show it.
    val table = base.applying(Proposals.stateReadRepricing).table
    assert(
      settledCost(table, Opcode.SLoad).contains(BigInt(200)) &&
        settledCost(table, Opcode.Balance).contains(BigInt(400)) &&
        settledCost(table, Opcode.ExtCodeSize).contains(BigInt(700)),
      "an operation charged through the table kept its baseline price"
    )
  }

  it should "leave every entry it does not name exactly as the baseline holds it" in {
    // Stated as the set that moved rather than as spot checks, so an entry
    // reached by accident fails as loudly as one missed on purpose.
    val named = Set(Opcode.SLoad, Opcode.Balance, Opcode.ExtCodeSize)
    val table = base.applying(Proposals.stateReadRepricing).table
    val moved =
      Opcode.values.toSet.filter(opcode => table.operationAt(opcode.code) != base.table.operationAt(opcode.code))
    assert(moved == named, s"the entries that moved were ${moved.toString} rather than ${named.toString}")
  }

  it should "leave the precompile prices as the same value, not an equal copy" in
    // The precompiles are built from the schedule too, so a proposal that
    // rebuilt them from the repriced one would look correct and would have
    // rewritten what it does not name.
    assert(
      base.applying(Proposals.stateReadRepricing).precompiles eq base.precompiles,
      "a repricing of operations rebuilt the precompile set"
    )
