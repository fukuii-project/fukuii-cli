package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.GasSchedule
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2028 changes, and the far larger set it must leave alone.
  *
  * ==One figure moves and the one beside it deliberately does not==
  *
  * *"The gas per non-zero byte is reduced from 68 to 16. Gas cost of zero bytes
  * is unchanged."* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-2028.md`, Final).
  * The two prices sit next to each other in the record and the document changes
  * the RATIO between them, so a delta that moved both would satisfy any check
  * asking merely that the non-zero price fell. The zero-byte case below is
  * therefore not a formality.
  *
  * ==This price is read at spend time, so moving the record IS the change==
  *
  * Unlike [[Eip1108]] and [[Eip1884]] at the same upgrade, nothing copies this
  * figure into an entry: a transaction's intrinsic cost is settled before any
  * invocation begins. `org.fukuii.evm.GasSchedule` states the three classes and
  * this field is in the first, which is why there is no entry to rebuild and no
  * paired case over one.
  */
class Eip2028Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.petersburg

  private val adopted: UpgradeRules = base.adopting(Eip2028.component)

  private val before: GasSchedule = base.evm.schedule

  private val after: GasSchedule = adopted.evm.schedule

  "adopting EIP-2028" should "reduce the price of a non-zero byte to sixteen" in
    // Asserted as this network's own literal. Both networks this repository
    // configures state 68 at genesis, so a comparison across the two would go
    // on agreeing with a mutation that moved both.
    assert(after.transactionDataPerNonZeroByte == BigInt(16), "the one price the document names did not reach 16")

  it should "have charged sixty-eight before it was adopted" in
    // The control. Without it the case above holds for a network that stated
    // the reduced price all along, and no proposal below this one moves it.
    assert(
      before.transactionDataPerNonZeroByte == BigInt(68),
      "the price this document reduces was already at its reduced value"
    )

  it should "leave the price of a zero byte exactly where it found it" in
    // The document is explicit that this one does not move, and the reduction
    // is argued from network delay rather than execution cost -- so what it
    // actually changes is the ratio between the two. A delta reducing both
    // would leave that ratio where it was.
    assert(
      after.transactionDataPerZeroByte == before.transactionDataPerZeroByte &&
        after.transactionDataPerZeroByte == BigInt(4),
      "a document that reprices what a transaction carries moved the price it says is unchanged"
    )

  it should "leave the transaction's own base charge alone" in
    // The third figure an intrinsic cost is settled from. Nothing in the
    // document names it, and it is the member a reader most easily conflates
    // with the per-byte prices because all three are spent together.
    assert(after.transactionBase == before.transactionBase, "the per-byte repricing reached the flat charge")

  it should "move no figure the document does not name" in
    // Stated as the whole record rather than as spot checks, so a price reached
    // by accident fails as loudly as the named one failing to move.
    assert(
      after == before.copy(transactionDataPerNonZeroByte = BigInt(16)),
      "the repriced record differs from the earlier one by something other than the one figure"
    )

  it should "leave the instruction table as the same value" in
    // Nothing copies this price into an entry, so a delta that rebuilt the
    // table would be doing work the document does not ask for and would be
    // indistinguishable by value from one that did not.
    assert(adopted.evm.table eq base.evm.table, "a price read at spend time was copied into the instruction table")

  it should "leave the precompile set as the same value" in
    assert(adopted.evm.precompiles eq base.evm.precompiles, "an intrinsic-cost repricing rebuilt the natives")

  it should "settle that figure and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(schedule = after),
      "the adopting rules differ from the earlier ones by something other than the record"
    )

  it should "reach no facet outside the machine" in
    // Worth asserting rather than assuming here: this is the one figure of the
    // six documents at this upgrade that a transaction rather than an
    // invocation spends, so the admission facet is where a reader would most
    // expect it to land.
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a price a transaction spends was settled outside the machine's own record"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(2028)), "the journal must record what was adopted")
