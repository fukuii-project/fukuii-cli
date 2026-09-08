package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.fukuii.evm.{Cost, Opcode}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3855 changes, and what it must leave alone.
  *
  * ==The tier is asserted against the schedule, not against 2==
  *
  * The document names a figure and a tier in one sentence -- *"The cost of this
  * instruction is 2 gas (aka `base`)"* -- and the two are not the same claim. A
  * spec asserting the number would pass for a build that wrote 2 into the entry
  * and stopped tracking the tier, which is the defect: a later repricing of the
  * tier would then leave this operation behind.
  */
class Eip3855Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.paris

  private val adopted: UpgradeRules = base.adopting(Eip3855.component)

  "adopting EIP-3855" should "put an operation at 0x5f" in
    assert(adopted.evm.table.contains(Opcode.Push0), "the document introduces the instruction at that byte")

  it should "not have been there before it was adopted" in
    assert(
      !base.evm.table.contains(Opcode.Push0),
      "the fork below already carried it, leaving the change untested"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(3855)),
      "the journal states which document produced these rules"
    )

  it should "price it at the base tier the document names" in
    assert(
      adopted.evm.table.operationAt(0x5f).map(_.cost) == Some(Cost.Fixed(adopted.evm.schedule.base)),
      "the entry is built from the tier, so a proposal moving the tier moves this operation with it"
    )

  it should "price it below the family whose name it shares" in
    // The failure this catches is the operation being admitted to the push
    // family, which is priced a tier higher. Both tiers are read from the
    // schedule rather than written out, so this compares the rule against the
    // rule and not against two literals.
    assert(
      adopted.evm.table.operationAt(0x5f).map(_.cost) != adopted.evm.table.operationAt(0x60).map(_.cost) &&
        adopted.evm.schedule.base < adopted.evm.schedule.veryLow,
      "base is not very low, and the operation is priced at the first"
    )

  it should "add exactly one operation" in
    assert(adopted.evm.table.size == base.evm.table.size + 1, "one entry joins the table and nothing leaves it")

  it should "move no price at all" in
    // The tier the entry is built from is already what the rules hold, so this
    // document adds an operation without repricing anything.
    assert(adopted.evm.schedule == base.evm.schedule, "no figure moves, and the two schedules are the same value")

  it should "leave every other facet alone" in
    assert(
      (adopted.execution, adopted.admission, adopted.consensus, adopted.header) ==
        (base.execution, base.admission, base.consensus, base.header),
      "one entry in one facet is the whole delta"
    )

  "the same component" should "compose over a proof-of-work rule set" in {
    // The operation reads nothing outside the frame -- not a header field, not
    // a network identifier, not a beacon -- so there is no value a proof-of-work
    // network would lack. That is what makes this document adoptable
    // independently of the rest of the upgrade that shipped it.
    val powBase = ethereumclassic.Upgrades.mystique
    val powAdopted = powBase.adopting(Eip3855.component)
    assert(
      powAdopted.evm.table.contains(Opcode.Push0) && !powBase.evm.table.contains(Opcode.Push0),
      "the entry lands on a proof-of-work rule set exactly as it lands on this one"
    )
  }
