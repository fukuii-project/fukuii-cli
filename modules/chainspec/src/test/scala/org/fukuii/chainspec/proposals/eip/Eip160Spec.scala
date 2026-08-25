package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, Opcode, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-160 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip160.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * Every figure below is read from the proposal's own text. The machine knows
  * how to apply a delta and knows nothing about which delta is right.
  */
class Eip160Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.tangerineWhistle

  private val adopted: UpgradeRules = base.adopting(Eip160.component)

  "adopting EIP-160" should "raise the charge for each byte of an exponent to 50" in
    assert(
      adopted.evm.schedule.expPerByte == BigInt(50),
      "the one price the proposal names did not move"
    )

  it should "leave the settled part of the charge at 10" in
    // The half a delta is most likely to get wrong, because the two are one
    // expression at the site that spends them. The document raises "10 + 10 per
    // byte" to "10 + 50 per byte", so a delta moving both would produce a
    // figure that is plausible and wrong on every network from this fork on.
    assert(
      adopted.evm.schedule.expBase == BigInt(10),
      "the base of the exponent charge moved, and no fork moves it"
    )

  it should "have charged 10 for each byte before it was adopted" in
    // The control. Without it the two cases above pass against a schedule that
    // already held the raised figure.
    assert(
      base.evm.schedule.expPerByte == BigInt(10),
      "the preceding rules already charged what this document is supposed to raise"
    )

  it should "move that price and no other" in
    // Stated as the whole record rather than as spot checks, so a field reached
    // by accident fails as loudly as the named one failing to move. A schedule
    // carries dozens of prices and a repricing that caught a neighbour would be
    // invisible to any assertion naming only what the document names.
    assert(
      adopted.evm.schedule == base.evm.schedule.copy(expPerByte = BigInt(50)),
      "the repriced schedule differs from the earlier one by something other than the exponent's per-byte charge"
    )

  it should "leave the table as the same value, not an equal copy" in
    // The difference from EIP-150, and the reason this component needs no
    // `table.adding` call. EXP works its charge out from the exponent it was
    // handed, so the table holds no number for it; a delta that rebuilt the
    // table here would be reaching past what the document changes.
    assert(
      adopted.evm.table eq base.evm.table,
      "a price read only where it is spent rebuilt the table"
    )

  it should "leave the precompile prices as the same value" in
    assert(
      adopted.evm.precompiles eq base.evm.precompiles,
      "repricing an operation rebuilt the precompile set"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a repricing altered a facet it does not name"
    )

  "the table on both sides of this document" should "leave EXP working out its own price" in {
    // What makes leaving the table alone correct rather than merely observed.
    // Were EXP ever given a settled entry, the interpreter would keep reading
    // the schedule at the moment it spends and the entry's figure would be
    // charged by nothing -- a price with two homes and no reader, which is
    // silent in the one direction the schedule's own taxonomy does not cover.
    val settled = Vector(base, adopted).flatMap(rules =>
      rules.evm.table.operationAt(Opcode.Exp.code).collect { case Operation(_, Cost.Fixed(gas)) => gas }
    )
    assert(settled.isEmpty, "a fork settled a price for an operation that works out its own")
  }
