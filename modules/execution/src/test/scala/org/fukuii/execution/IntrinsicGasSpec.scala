package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.Bytes
import org.fukuii.evm.EvmFixtures

/** What a transaction is charged before it runs, priced from the schedule it
  * was handed.
  *
  * ==The schedule is a made-up one, and that is what makes these mean
  * anything==
  *
  * `EvmFixtures.schedule` holds a distinct value in every field, none of them a
  * price any network launched with. A charge asserted against a network's real
  * numbers cannot tell an implementation that reads the schedule from one that
  * has the number compiled in, and it cannot tell one field from another where
  * two of them agree.
  */
class IntrinsicGasSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule

  private def charged(data: Bytes, deploys: Boolean = false): BigInt =
    IntrinsicGas.of(schedule, data, deploys)

  "a transaction carrying nothing" should "be charged the base price alone" in
    assert(
      charged(Bytes.Empty) == schedule.transactionBase,
      "the floor every transaction pays is the schedule's, and nothing else applies to an empty one"
    )

  "a zero byte" should "be charged the zero-byte price" in
    assert(
      charged(EvmFixtures.bytesOf("0x00")) == schedule.transactionBase + schedule.transactionDataPerZeroByte,
      "a byte that is zero is priced by the field named for it, which is cheaper than the other"
    )

  "a non-zero byte" should "be charged the non-zero-byte price" in
    assert(
      charged(EvmFixtures.bytesOf("0xff")) == schedule.transactionBase + schedule.transactionDataPerNonZeroByte,
      "a byte that is not zero is priced by the other field, and the two must not be reachable from one another"
    )

  "a mixture" should "charge each byte once, at the price its own value names" in
    // Two zeroes and three others. A count taken over one partition and applied
    // to both would agree with this only where the two prices did.
    assert(
      charged(EvmFixtures.bytesOf("0x0001000203")) ==
        schedule.transactionBase + schedule.transactionDataPerZeroByte * 2 +
        schedule.transactionDataPerNonZeroByte * 3,
      "the bytes are partitioned by value and each partition priced once"
    )

  "a deployment" should "pay the creation surcharge on top of everything else" in
    assert(
      charged(Bytes.Empty, deploys = true) == schedule.transactionBase + schedule.transactionCreate,
      "a transaction stating no recipient deploys, and the schedule prices that separately"
    )

  it should "still be charged for the data it carries" in
    // The surcharge is added to the data charge rather than replacing it.
    assert(
      charged(EvmFixtures.bytesOf("0xff"), deploys = true) - charged(Bytes.Empty, deploys = true) ==
        schedule.transactionDataPerNonZeroByte,
      "init code is data and is paid for as data"
    )

  "a call" should "be charged less than a deployment carrying the same data" in
    // The control, and it varies the one input the cases above turn on rather
    // than restating what an empty transaction costs. Three things make the two
    // price alike and each leaves those cases holding over an input that
    // decides nothing: a surcharge charged on every transaction, a surcharge
    // charged on none, and a schedule whose surcharge is zero. What the
    // surcharge amounts to is the case above; that it separates the two at all
    // is this.
    assert(
      charged(Bytes.Empty, deploys = true) > charged(Bytes.Empty),
      "a transaction naming a recipient creates nothing, so it must not be priced as one that does"
    )
