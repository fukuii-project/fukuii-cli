package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.AccessTuple

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

  private def charged(
      data: Bytes,
      deploys: Boolean = false,
      declared: Seq[AccessTuple] = Seq.empty
  ): BigInt =
    IntrinsicGas.of(schedule, data, deploys, declared)

  /** An address distinguishable from every other in this file by its last byte.
    */
  private def account(last: Int): Address =
    Address.fromBytesTruncating(IArray.fill(19)(0.toByte) :+ last.toByte)

  /** A storage key the same way. */
  private def slot(last: Int): Hash =
    Hash.fromBytesTruncating(IArray.fill(31)(0.toByte) :+ last.toByte)

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

  "a declared account" should "be charged the schedule's per-address price" in
    assert(
      charged(Bytes.Empty, declared = Seq(AccessTuple(account(1), Seq.empty))) - charged(Bytes.Empty) ==
        schedule.transactionAccessListAddress,
      "declaring an account is priced from the record, not from a literal"
    )

  "a declared storage key" should "be charged the schedule's per-key price on top of its account's" in
    assert(
      charged(Bytes.Empty, declared = Seq(AccessTuple(account(1), Seq(slot(1))))) -
        charged(Bytes.Empty, declared = Seq(AccessTuple(account(1), Seq.empty))) ==
        schedule.transactionAccessListStorageKey,
      "a key adds its own price to the account's rather than replacing it"
    )

  it should "be charged per key rather than per account holding keys" in
    // Two keys under one account, against one. An implementation charging the
    // account and ignoring the count answers the same for both.
    assert(
      charged(Bytes.Empty, declared = Seq(AccessTuple(account(1), Seq(slot(1), slot(2))))) -
        charged(Bytes.Empty, declared = Seq(AccessTuple(account(1), Seq(slot(1))))) ==
        schedule.transactionAccessListStorageKey,
      "the keys are counted, not merely noticed"
    )

  "a declaration repeating an account" should "be charged for both occurrences" in
    // EIP-2930: "non-unique addresses and storage keys are not disallowed,
    // though they will be charged for multiple times". An implementation
    // reaching for a set before charging answers one address here, and
    // undercharges every transaction anyone can construct this way.
    assert(
      charged(Bytes.Empty, declared = Seq(AccessTuple(account(1), Seq.empty), AccessTuple(account(1), Seq.empty))) ==
        charged(Bytes.Empty) + schedule.transactionAccessListAddress * 2,
      "the declaration is counted as encoded, and a duplicate is a second entry"
    )

  "a declaration repeating a storage key under one account" should "be charged for both occurrences" in
    // The same rule on the other half of the pair, which a set keyed on the
    // account alone would still get right.
    assert(
      charged(Bytes.Empty, declared = Seq(AccessTuple(account(1), Seq(slot(1), slot(1))))) ==
        charged(Bytes.Empty) + schedule.transactionAccessListAddress +
        schedule.transactionAccessListStorageKey * 2,
      "a repeated key is a second key for the charge"
    )

  "a transaction declaring nothing" should "be charged exactly what it was before the term existed" in
    // The control for every case above: the term must vanish for an empty
    // declaration, or each of them is measuring a constant offset rather than a
    // price.
    assert(
      charged(EvmFixtures.bytesOf("0xff00"), declared = Seq.empty) ==
        schedule.transactionBase + schedule.transactionDataPerNonZeroByte + schedule.transactionDataPerZeroByte,
      "an empty declaration adds nothing at all"
    )
