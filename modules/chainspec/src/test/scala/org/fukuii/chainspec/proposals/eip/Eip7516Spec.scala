package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.evm.{Cost, Opcode}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-7516 changes, and what it must leave alone.
  *
  * The document adds one operation and reprices nothing, which is
  * [[Eip3198Spec]]'s shape one fork later -- and for the same reason, since
  * EIP-7516 describes itself as *"identical to EIP-3198 (`BASEFEE` opcode)
  * except that it returns the blob base-fee"* (`ethereum/EIPs` @ `d2a64c2d4`
  * (2026-09-11), `EIPS/eip-7516.md:16`, Final).
  */
class Eip7516Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.shanghai

  private val adopted: UpgradeRules = base.adopting(Eip7516.component)

  "adopting EIP-7516" should "put the operation in the table" in
    assert(
      adopted.evm.table.operationAt(0x4a).map(_.opcode).contains(Opcode.BlobBaseFee),
      "the byte 0x4a runs an operation at these rules and ran none below them"
    )

  it should "not have carried it before it was adopted" in
    assert(
      base.evm.table.operationAt(0x4a).isEmpty,
      "the fork below meets the byte as one naming no operation"
    )

  it should "price it at the base tier rather than at a literal" in
    // Named as the tier for `Eip3198`'s reason: a proposal that moved the base
    // tier would move this operation with it, and a literal 2 here would opt
    // the operation out of its own tier silently.
    assert(
      adopted.evm.table.operationAt(0x4a).map(_.cost).contains(Cost.Fixed(base.evm.schedule.base)) &&
        base.evm.schedule.base == BigInt(2),
      "the document states a cost of 2 and both corroborating sources state it as the base tier"
    )

  it should "record the adoption" in
    assert(
      adopted.components.lastOption.contains(ProposalId.Eip(7516)),
      "the journal states which document produced these rules"
    )

  it should "move no price at all" in
    assert(
      adopted.evm.schedule == base.evm.schedule,
      "the tier this entry is built from is already what the rules hold, so nothing is repriced"
    )

  it should "be the whole of the delta, and reach no facet but the machine's" in
    assert(
      adopted.evm == base.evm.copy(table = adopted.evm.table) &&
        adopted.header == base.header &&
        adopted.execution == base.execution &&
        adopted.admission == base.admission,
      "one table entry, built through the machine-scoped constructor that cannot reach any other facet"
    )

  "the operation it adds" should "be unrunnable without the document that prices blob gas" in
    // The relationship `Eip3198` records with `Eip1559`, one fork later. This
    // composition holds the operation and no update fraction, which is a rule
    // set `org.fukuii.evm.Interpreter` refuses rather than defaulting -- and the
    // refusal is asserted where the machine is, in
    // `org.fukuii.evm.BlobBaseFeeSpec`.
    assert(
      adopted.evm.blobBaseFeeUpdateFraction.isEmpty,
      "adopting the operation alone leaves the machine with nothing to derive a charge from"
    )

  it should "become runnable once that document is adopted beside it" in
    assert(
      base.adopting(Eip4844.component, Eip7516.component).evm.blobBaseFeeUpdateFraction.isDefined,
      "the pair is what a fork adopts, and the order between them is immaterial: the deltas are disjoint"
    )
