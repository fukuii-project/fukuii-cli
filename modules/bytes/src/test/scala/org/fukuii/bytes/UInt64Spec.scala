package org.fukuii.bytes

import org.scalatest.flatspec.AnyFlatSpec

/** The machine word, and specifically the half of its range a signed reading
  * gets wrong.
  *
  * Two of these pin failures that are cheap to reintroduce and expensive to
  * find, because both produce a plausible value rather than an error: reading a
  * high byte as a sign, and emitting a two's-complement leading zero into a
  * scalar. The second is the one that gets a client's messages rejected by its
  * peers rather than failing locally.
  */
class UInt64Spec extends AnyFlatSpec:

  private val max = BigInt(2).pow(64) - 1

  "fromLong" should "accept a non-negative value" in
    assert(UInt64.fromLong(1000L).map(_.toBigInt) == Right(BigInt(1000)), "an ordinary quantity")

  it should "reject a negative rather than reinterpreting its bits" in
    assert(UInt64.fromLong(-1L) == Left(BytesError.OutOfRange), "a caller with -1 means -1, not 2^64 - 1")

  "fromBits" should "take the same bits as a value near the top of the range" in
    assert(UInt64.fromBits(-1L).toBigInt == max, "the bit pattern is the maximum, read unsigned")

  "fromBigInt" should "accept 2^64 - 1" in
    assert(UInt64.fromBigInt(max).map(_.toBigInt) == Right(max), "the largest machine word")

  it should "reject 2^64" in
    assert(UInt64.fromBigInt(BigInt(2).pow(64)) == Left(BytesError.OutOfRange), "one past the top is not a word")

  it should "reject a negative value" in
    assert(UInt64.fromBigInt(BigInt(-1)) == Left(BytesError.OutOfRange), "the type is unsigned")

  "fromBytes" should "read an empty sequence as zero" in
    assert(UInt64.fromBytes(IArray.empty[Byte]).map(_.toBigInt) == Right(BigInt(0)), "RLP spells zero as no bytes")

  it should "read eight 0xff bytes as the maximum" in {
    val bytes = Hex.decode("ff" * 8).toOption.get
    assert(UInt64.fromBytes(bytes).map(_.toBigInt) == Right(max), "a full-width read must not overflow or go negative")
  }

  /** A signed reading of this byte gives -128. That mistake produces a
    * plausible-looking negative quantity rather than an error, and it has
    * reached the wire before.
    */
  it should "read the high bit as magnitude, never as a sign" in {
    val bytes = Hex.decode("80").toOption.get
    assert(UInt64.fromBytes(bytes).map(_.toBigInt) == Right(BigInt(128)), "two's-complement reading would give -128")
  }

  it should "reject nine bytes" in {
    val bytes = Hex.decode("ff" * 9).toOption.get
    assert(UInt64.fromBytes(bytes) == Left(BytesError.BadWidth(8, 9)), "nine bytes is not a machine word")
  }

  "toBytes" should "render zero as eight zero bytes" in
    assert(Hex.encode(UInt64.Zero.toBytes) == "00" * 8, "the fixed form is padded, not empty")

  it should "render the maximum as eight 0xff bytes" in
    assert(Hex.encode(UInt64.MaxValue.toBytes) == "ff" * 8, "the top of the range round-trips")

  "toMinimalBytes" should "render zero as no bytes at all" in
    assert(UInt64.Zero.toMinimalBytes.length == 0, "the scalar rule gives zero an empty encoding")

  /** The two's-complement form of 128 is `[0x00, 0x80]`, and a scalar carrying
    * that leading zero is not canonical RLP — peers reject the message rather
    * than the local node noticing.
    */
  it should "emit no leading zero for a value whose high bit is set" in {
    val value = UInt64.fromLong(128L).toOption.get
    assert(Hex.encode(value.toMinimalBytes) == "80", "one byte, not two")
  }

  it should "render the maximum without padding" in
    assert(Hex.encode(UInt64.MaxValue.toMinimalBytes) == "ff" * 8, "every byte is significant here")

  /** `toString` is the underlying signed value's and cannot be overridden on an
    * opaque type, so anything a human reads goes through `show`.
    */
  "show" should "print the unsigned value where toString would print a negative" in
    assert(UInt64.MaxValue.show == max.toString, "the printed form must be the value")

  // The comparison is the one place a signed delegate is not merely untidy but
  // wrong: it orders everything at or above 2^63 below zero.

  "the Ordering" should "place the maximum above zero, not below it" in {
    val values = List(UInt64.MaxValue, UInt64.Zero)
    assert(values.sorted.map(_.toBigInt) == List(BigInt(0), max), "a signed comparison would invert this")
  }

  it should "sort the whole range ascending" in {
    val values = List(UInt64.MaxValue, UInt64.fromLong(1L).toOption.get, UInt64.Zero, UInt64.fromBits(Long.MinValue))
    assert(
      values.sorted.map(_.toBigInt) == List(BigInt(0), BigInt(1), BigInt(2).pow(63), max),
      "2^63 sits between 1 and the maximum, not below zero"
    )
  }

  "a machine word" should "be found again as a Map key" in {
    val key = UInt64.fromLong(100000L).toOption.get
    val other = UInt64.fromBytes(Hex.decode("0186a0").toOption.get).toOption.get
    assert(Map(key -> "found").get(other).contains("found"), "equality is by value, not by construction route")
  }
