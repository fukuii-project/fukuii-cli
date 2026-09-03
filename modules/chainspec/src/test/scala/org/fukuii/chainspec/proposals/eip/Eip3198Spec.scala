package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Cost, Opcode, Operation}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3198 changes, and the far larger set it must leave alone.
  *
  * ==The entry names a TIER, and a literal of the same value would pass
  * everything but one case below==
  *
  * *"Add a `BASEFEE` opcode at `(0x48)`, with gas cost `G_base`"*
  * (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-3198.md`, Final), and `G_base` is 2
  * on both networks this repository configures. So an entry built from the
  * literal 2 and an entry built from the tier are the same value here and differ
  * only on a network that reprices the tier. The case that separates them applies
  * the delta to rules stating something else -- [[Eip1344Spec]] carries the same
  * pair for the same reason.
  *
  * ==Here rather than with the machine, because these are the document's claims==
  *
  * The byte and the tier below are read from the proposal's own text. What the
  * operation PUSHES -- and in particular that it reads the block's own base fee,
  * and refuses rather than defaulting where a block carries none -- is the
  * machine's, and `org.fukuii.evm`'s own specs certify it there.
  */
class Eip3198Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.berlin

  private val adopted: UpgradeRules = base.adopting(Eip3198.component)

  "adopting EIP-3198" should "not already be in the table it adds to" in
    assert(base.evm.table.operationAt(0x48).isEmpty, "the preceding table already ran an operation at this byte")

  it should "place BASEFEE at 0x48" in
    assert(
      adopted.evm.table.operationAt(0x48).map(_.opcode).contains(Opcode.BaseFee),
      "the document names 0x48"
    )

  it should "price it from the base tier" in
    assert(
      adopted.evm.table.operationAt(0x48).map(_.cost).contains(Cost.Fixed(base.evm.schedule.base)),
      "BASEFEE must be charged the tier its document names"
    )

  it should "charge exactly two on this network" in
    // The tier's own value, stated as this network's literal. The case above
    // reads the figure out of the same record the delta reads it from, so the
    // two would agree with each other whatever that record held; only a literal
    // says which number a node actually charges. The document's own table gives
    // the same figure a third way, as a Cost column reading 2.
    assert(
      adopted.evm.table.operationAt(0x48).map(_.cost).contains(Cost.Fixed(BigInt(2))),
      "this network states its base tier at 2, so BASEFEE costs 2 here"
    )

  it should "follow the tier onto a network that prices it differently" in {
    // The case that separates the tier from a literal of the same value. A
    // delta naming 2 answers 2 here; one naming the tier answers 9.
    val elsewhere = base.copy(evm = base.evm.copy(schedule = base.evm.schedule.copy(base = 9)))
    assert(
      elsewhere.adopting(Eip3198.component).evm.table.operationAt(0x48).map(_.cost).contains(Cost.Fixed(BigInt(9))),
      "a network repricing the base tier did not reprice the operation the document puts on it"
    )
  }

  it should "not be confused with the operation one byte below it" in
    // 0x47 is SELFBALANCE and it is also a base-tier zero-input push, added two
    // upgrades earlier. A delta off by one byte would land on an occupied slot
    // rather than failing, so the neighbor is named rather than left implied.
    assert(
      adopted.evm.table.operationAt(0x47).map(_.opcode).contains(Opcode.SelfBalance),
      "the entry displaced the operation below the byte the document names"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 1,
      "adopting a document that introduces one operation moved the table by some other amount"
    )

  it should "move no figure at all" in
    // The document introduces an operation and reprices nothing, so the record
    // must survive as the same value. Stated as identity because a rebuilt copy
    // would be indistinguishable by value and would hide a price moving beside
    // the addition.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document that reprices nothing rebuilt the record"
    )

  it should "leave the precompile set as the same value" in
    assert(
      adopted.evm.precompiles eq base.evm.precompiles,
      "an operation added to the instruction set reached the natives"
    )

  it should "settle that entry and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as the named one failing to move.
    assert(
      adopted.evm == base.evm
        .copy(table = base.evm.table.adding(Operation(Opcode.BaseFee, Cost.Fixed(base.evm.schedule.base)))),
      "the adopting rules differ from the earlier ones by something other than the entry"
    )

  it should "reach no facet outside the machine" in
    // In particular NOT the admission facet. This document arrives at the same
    // upgrade as the one that admits a fee-market format, and the two are easy
    // to conflate: both are named for the base fee. Only the other one admits
    // anything.
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "an operation added to the machine altered a facet the document does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(3198)), "the journal must record what was adopted")
