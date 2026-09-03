package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3529 changes across two facets, and what it must not.
  *
  * ==Three deltas, and each control names a specific wrong figure==
  *
  * The document numbers its own three changes, so each has a case. The controls
  * below state the EARLIER figure rather than checking a member for emptiness,
  * so a delta that moved the wrong member still fails -- and the two
  * storage-refund figures beside the one that moves are asserted unchanged by
  * value, because they are the members a delta written from the wrong reading
  * would take with it.
  *
  * ==The facet crossing is itself under test==
  *
  * This is the first document in a long while to write the execution facet, and
  * the only one in this upgrade to write two facets at once. A component built
  * by copying a machine-only sibling would compile, adopt, move both prices, and
  * leave the divisor where it was.
  */
class Eip3529Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.berlin

  private val adopted: UpgradeRules = base.adopting(Eip3529.component)

  "adopting EIP-3529" should "replace the clearing refund with four thousand eight hundred" in
    assert(
      adopted.evm.schedule.refundNetStorageClear == BigInt(4800),
      "the document states 4,800 gas outright and derives it from two earlier proposals"
    )

  it should "have refunded fifteen thousand for a clearing before it was adopted" in
    assert(
      base.evm.schedule.refundNetStorageClear == BigInt(15000),
      "EIP-2200's figure is what this document replaces"
    )

  it should "remove the refund for ending an invocation" in
    assert(
      adopted.evm.schedule.refundSelfDestruct == BigInt(0),
      "the document's first numbered change is to remove this refund"
    )

  it should "have refunded twenty-four thousand for it before" in
    assert(
      base.evm.schedule.refundSelfDestruct == BigInt(24000),
      "the figure this document removes"
    )

  it should "bound the refund at a fifth of the gas used" in
    assert(
      adopted.execution.maxRefundQuotient == BigInt(5),
      "the document's parameter table states MAX_REFUND_QUOTIENT as 5"
    )

  it should "have bounded it at a half before" in
    assert(
      base.execution.maxRefundQuotient == BigInt(2),
      "the document's own remark is that the bound was gas_used // 2"
    )

  it should "NOT move the two storage refunds beside the one it replaces" in
    // The members a delta written from a careless reading would take with it.
    // Measured rather than assumed: the storage instructions' refund arithmetic
    // is byte-identical between the two forks' modules, so only the constant
    // moves and these two stay.
    assert(
      adopted.evm.schedule.refundNetStorageResetFromZero == base.evm.schedule.refundNetStorageResetFromZero &&
        adopted.evm.schedule.refundNetStorageReset == base.evm.schedule.refundNetStorageReset,
      "a document replacing one refund figure moved a second one beside it"
    )

  it should "NOT move the legacy metering refund that holds the same figure" in
    // refundStorageClear also holds 15,000, and is the field a network still on
    // pre-EIP-2200 metering would read. This network is not, so moving it would
    // state a price nothing spends -- and a delta that moved it instead of the
    // net field would pass every case that only checks for a 4,800 somewhere.
    assert(
      adopted.evm.schedule.refundStorageClear == BigInt(15000),
      "the legacy metering field is not this document's to move on this network"
    )

  it should "add no operation and reprice nothing else" in
    assert(
      (adopted.evm.table eq base.evm.table) &&
        adopted.evm.schedule == base.evm.schedule.copy(
          refundNetStorageClear = BigInt(4800),
          refundSelfDestruct = BigInt(0)
        ),
      "the schedule differs from the earlier one by something other than the two refunds"
    )

  it should "settle one member on the execution facet and nothing else" in
    assert(
      adopted.execution == base.execution.copy(maxRefundQuotient = BigInt(5)),
      "the execution rules differ by something other than the divisor"
    )

  it should "reach neither admission nor consensus" in
    assert(
      (adopted.admission eq base.admission) && (adopted.consensus eq base.consensus),
      "a refund document altered a facet it does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(3529)), "the journal must record what was adopted")
