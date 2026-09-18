package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{BlobSchedule, UpgradeRules}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-7691 changes, and what it must leave alone.
  *
  * The document moves three numbers and adds no mechanism, so the whole of what
  * there is to check is that all three move, that they land on the two records
  * that already hold them, and that nothing else does.
  *
  * **The base is the rule set that already carries the accounting**, because a
  * widening has nothing to widen otherwise: the assertions below are about the
  * difference between two rule sets, and would be about a rule set's own
  * construction if the base carried no schedule at all.
  */
class Eip7691Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.cancun

  private val adopted: UpgradeRules = base.adopting(Eip7691.component)

  "adopting EIP-7691" should "widen the per-block blob target and maximum together" in
    assert(
      adopted.header.blobSchedule.contains(BlobSchedule(targetBlobs = BigInt(6), maxBlobs = BigInt(9))),
      "both members move, and a fork moving one without the other prices blobs against a bound it does not enforce"
    )

  it should "raise the divisor the fee update reads" in
    assert(
      adopted.evm.blobBaseFeeUpdateFraction.contains(BigInt(5007716)),
      "the excess is unchanged and what it is divided by is not"
    )

  it should "have widened rather than introduced, on both records" in
    // The check that this document is a repricing: the base must already state
    // both quantities, or the two assertions above would pass over a rule set
    // that had simply gained blob accounting here.
    assert(
      base.header.blobSchedule.isDefined && base.evm.blobBaseFeeUpdateFraction.isDefined,
      "the base already accounts for blob gas, so this document moves values rather than adding a mechanism"
    )

  it should "have moved every one of the three" in
    // The negative half of the widening, stated against the base's own values
    // rather than against literals, so a change to the base cannot leave this
    // test agreeing with both forks at once.
    assert(
      base.header.blobSchedule != adopted.header.blobSchedule &&
        base.evm.blobBaseFeeUpdateFraction != adopted.evm.blobBaseFeeUpdateFraction,
      "each record the document names differs from the fork below it"
    )

  it should "leave the opcode table untouched" in
    // A repricing of a header-accounted resource reaches no operation. The
    // blob-fee opcode reads the quantities this document moves and is not
    // itself moved by it.
    assert(
      adopted.evm.table == base.evm.table,
      "the machine's operations are what this document does not touch"
    )

  it should "leave what a transaction may offer untouched" in
    assert(
      adopted.admission == base.admission,
      "a wider block admits no new transaction FORM, only more of the same one"
    )
