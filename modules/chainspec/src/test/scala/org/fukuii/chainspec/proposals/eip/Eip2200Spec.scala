package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{GasSchedule, StorageMetering}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2200 changes: one switch, four figures, and one figure it
  * must be kept away from.
  *
  * ==`storageLoad` IS NOT THIS DOCUMENT'S, AND THAT IS THE POINT OF THIS SPEC==
  *
  * *"`SLOAD_GAS`: changed from `200` to `800`"* appears in this document and in
  * [[Eip1884]], both land at the same upgrade, and both are quoting the same
  * constant of the executable specification. This schedule splits it three ways
  * because a network can move the operation without moving the metering: the
  * `SLOAD` OPERATION's price is `org.fukuii.evm.GasSchedule.storageLoad` and is
  * EIP-1884's, while the two members carrying the same quantity INSIDE the
  * `SSTORE` calculation are this document's.
  *
  * A delta here that also moved `storageLoad` would reach the right value by
  * the wrong route -- correct at this upgrade, because the other document moves
  * it to the same 800, and wrong the moment either network reprices one without
  * the other. Nothing compares the three fields, so nothing else would report
  * it. The case below asserts the field stays put, and [[Eip1884Spec]] asserts
  * the mirror.
  *
  * ==The two refunds are differences the document never writes as numbers==
  *
  * *"add `SSTORE_SET_GAS - SLOAD_GAS` to refund counter"* and *"add
  * `SSTORE_RESET_GAS - SLOAD_GAS` gas to refund counter"*, over the moved
  * variable: `20000 - 800` and `5000 - 800`. A reader checking the literals
  * below against the document's text will find expressions rather than figures,
  * which is why the arithmetic is asserted here against the two operands that
  * the same document lists as unchanged.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The switch's own RULE -- the refusal when a frame holds no more than the
  * stipend -- is the machine's, and `org.fukuii.evm`'s `StorageSentrySpec`
  * certifies it there.
  */
class Eip2200Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.petersburg

  private val adopted: UpgradeRules = base.adopting(Eip2200.component)

  private val before: GasSchedule = base.evm.schedule

  private val after: GasSchedule = adopted.evm.schedule

  "adopting EIP-2200" should "put the net scheme with a sentry in force" in
    assert(
      adopted.evm.storageMetering == StorageMetering.NetWithSentry,
      "the document restates EIP-1283's clauses and adds one refusal in front of them"
    )

  it should "have run the legacy scheme before it was adopted" in
    // The control, as the specific earlier case rather than as "not
    // NetWithSentry". This network reaches that state by adopting the net
    // scheme at Constantinople and withdrawing it at Petersburg, so the fork
    // below this one prices storage the pre-EIP-1283 way.
    assert(
      base.evm.storageMetering == StorageMetering.Legacy,
      "the fork below this one runs the scheme EIP-1716 restored"
    )

  it should "move the two clauses that spend the moved variable" in
    // "If *current value* equals *new value* (this is a no-op), `SLOAD_GAS` is
    // deducted" and "If *original value* does not equal *current value* (this
    // storage slot is dirty), `SLOAD_GAS` gas is deducted", ethereum/EIPs @
    // dbfa6bee, EIPS/eip-2200.md, Final. Asserted as this network's own
    // literals: both networks state 200 here, so a comparison across the two
    // would go on agreeing with a mutation that moved both.
    assert(
      after.netStorageNoop == BigInt(800) && after.netStorageDirty == BigInt(800),
      "a clause spending SLOAD_GAS was left at the figure it spent before"
    )

  it should "move the two refunds that are defined as differences from it" in
    // 20000 - 800 and 5000 - 800, against EIP-1283's 19800 and 4800 over the
    // old 200.
    assert(
      after.refundNetStorageResetFromZero == BigInt(19200) && after.refundNetStorageReset == BigInt(4200),
      "a refund defined as a difference from SLOAD_GAS did not move with it"
    )

  it should "leave those two refunds consistent with the operands they are differences of" in
    // The arithmetic rather than the literals. The document publishes
    // expressions, so a transcription error in either figure above is invisible
    // to a case that only checks what was transcribed.
    assert(
      after.refundNetStorageResetFromZero == after.netStorageInit - after.netStorageNoop &&
        after.refundNetStorageReset == after.netStorageClean - after.netStorageNoop,
      "a refund does not equal the difference the document defines it as"
    )

  it should "NOT move the price of the SLOAD operation" in
    // THE case this spec exists for. That figure is EIP-1884's, moves to the
    // same 800 at this same upgrade, and is named SLOAD_GAS by both documents.
    // A delta reaching it here is right by accident today and wrong on the
    // first network that reprices one without the other.
    assert(
      after.storageLoad == before.storageLoad,
      "this document moved the SLOAD operation's own price, which belongs to EIP-1884"
    )

  it should "have left that price at the figure the fork below charges" in
    // The named value behind the case above, so that a failure says which
    // figure was reached rather than only that something moved. Stated as a
    // literal because the two fields hold the same number after both documents
    // are adopted, and only the pre-adoption figure tells them apart here.
    assert(
      after.storageLoad == BigInt(200),
      "the SLOAD operation's price is not this document's to move, so it must still read the fork below's figure"
    )

  it should "leave the three figures the document lists as not changed" in
    // "SSTORE_SET_GAS", "SSTORE_RESET_GAS" and "SSTORE_CLEARS_SCHEDULE", all
    // three named explicitly as unchanged. They are also the operands the two
    // refunds above are differences of, so a delta moving one would move a
    // refund with it and the consistency case would still pass.
    assert(
      after.netStorageInit == BigInt(20000) && after.netStorageClean == BigInt(5000) &&
        after.refundNetStorageClear == BigInt(15000),
      "a figure the document lists as not changed moved"
    )

  it should "move no figure the document does not name" in
    // Stated as the whole record rather than as spot checks, so a price reached
    // by accident fails as loudly as one of the four failing to move.
    assert(
      after == before.copy(
        netStorageNoop = BigInt(800),
        netStorageDirty = BigInt(800),
        refundNetStorageResetFromZero = BigInt(19200),
        refundNetStorageReset = BigInt(4200)
      ),
      "the repriced record differs from the earlier one by something other than the four figures"
    )

  it should "leave the instruction table as the same value" in
    // A store is priced from its operands rather than through an entry, so a
    // reader expecting a table change is expecting the wrong shape.
    assert(adopted.evm.table eq base.evm.table, "a storage-metering change reached the instruction table")

  it should "leave the precompile set as the same value" in
    assert(adopted.evm.precompiles eq base.evm.precompiles, "a storage-metering change rebuilt the natives")

  it should "settle that switch and those figures and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(schedule = after, storageMetering = StorageMetering.NetWithSentry),
      "the adopting rules differ from the earlier ones by something other than the switch and the record"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a storage-metering change altered a facet the document does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(2200)), "the journal must record what was adopted")
