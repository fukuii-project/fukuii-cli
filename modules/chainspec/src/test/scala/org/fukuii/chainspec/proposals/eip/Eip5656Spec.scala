package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.{ethereum, ethereumclassic}
import org.fukuii.evm.{Cost, Opcode}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-5656 changes, and what it must leave alone.
  *
  * ==The entry carries no number, and that is the assertion==
  *
  * Two of the three terms in the document's charge depend on the operands, so
  * there is no figure for the table to hold and
  * [[org.fukuii.evm.Cost.Computed]] is what an entry for it must be. A build
  * writing a settled price here would charge the same for copying one byte and
  * for copying a kilobyte -- which no case in a single-word corpus could tell
  * apart, so it is asserted against the entry rather than left to a run.
  */
class Eip5656Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.shanghai

  private val adopted: UpgradeRules = base.adopting(Eip5656.component)

  "adopting EIP-5656" should "put an operation at 0x5e" in
    assert(adopted.evm.table.contains(Opcode.MCopy), "the document introduces the instruction at that byte")

  it should "not have been there before it was adopted" in
    assert(
      !base.evm.table.contains(Opcode.MCopy),
      "the fork below already carried it, leaving the change untested"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(5656)),
      "the journal states which document produced these rules"
    )

  it should "leave the operation to work out its own price" in
    assert(
      adopted.evm.table.operationAt(0x5e).map(_.cost) == Some(Cost.Computed),
      "the charge depends on the length copied and on what memory has to grow to, so the table holds no figure"
    )

  it should "add exactly one operation" in
    assert(adopted.evm.table.size == base.evm.table.size + 1, "one entry joins the table and nothing leaves it")

  it should "move no price at all" in
    // The two figures the operation spends are already in every schedule: the
    // document places it in the yellow paper's `W_copy` group, so it joins the
    // family the three existing copying operations are priced by rather than
    // founding a family of its own.
    assert(adopted.evm.schedule == base.evm.schedule, "no figure moves, and the two schedules are the same value")

  it should "leave every other facet alone" in
    assert(
      (adopted.execution, adopted.admission, adopted.consensus, adopted.header) ==
        (base.execution, base.admission, base.consensus, base.header),
      "one entry in one facet is the whole delta"
    )

  "the same component" should "compose over a proof-of-work rule set" in {
    // The operation reaches one frame's own memory and nothing else -- no
    // header field, no account, no network identifier -- so there is no value a
    // proof-of-work network would lack. This is the narrowest independence claim
    // any component here makes.
    val powBase = ethereumclassic.Upgrades.spiral
    val powAdopted = powBase.adopting(Eip5656.component)
    assert(
      powAdopted.evm.table.contains(Opcode.MCopy) && !powBase.evm.table.contains(Opcode.MCopy),
      "the entry lands on a proof-of-work rule set exactly as it lands on this one"
    )
  }
