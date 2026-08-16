package org.fukuii.bytes

import org.scalatest.flatspec.AnyFlatSpec

/** Fixed examples for the word type. The corpus-driven scalar table lives in
  * [[UInt256PropSpec]] and this file holds no vectors of its own.
  *
  * The bound and the two byte forms are the contract; the collection tests are
  * here because the shape of this type was chosen for exactly that property,
  * and a value type that silently fails a `Map` lookup is the failure mode the
  * choice was made to avoid.
  */
class UInt256Spec extends AnyFlatSpec:

  private val maxHex = "ff" * UInt256.Width

  "fromBigInt" should "accept 2^256 - 1" in
    assert(UInt256.fromBigInt(BigInt(2).pow(256) - 1).isRight, "the largest word must be representable")

  it should "reject 2^256" in
    assert(UInt256.fromBigInt(BigInt(2).pow(256)) == Left(BytesError.OutOfRange), "one past the top is not a word")

  it should "reject a negative value" in
    assert(UInt256.fromBigInt(BigInt(-1)) == Left(BytesError.OutOfRange), "the type is unsigned")

  "fromLong" should "reject a negative long rather than wrapping it" in
    assert(UInt256.fromLong(-1L) == Left(BytesError.OutOfRange), "a negative must not become a huge positive")

  "fromBytes" should "read an empty sequence as zero" in
    assert(UInt256.fromBytes(IArray.empty[Byte]).map(_.toBigInt) == Right(BigInt(0)), "RLP spells zero as no bytes")

  it should "read 32 bytes of 0xff as the maximum" in {
    val bytes = Hex.decode(maxHex).toOption.get
    assert(
      UInt256.fromBytes(bytes).map(_.toBigInt) == Right(BigInt(2).pow(256) - 1),
      "a full-width read must not overflow"
    )
  }

  it should "read the high bit as magnitude, never as a sign" in {
    val bytes = Hex.decode("80").toOption.get
    assert(UInt256.fromBytes(bytes).map(_.toBigInt) == Right(BigInt(128)), "two's-complement reading would give -128")
  }

  it should "reject 33 bytes" in {
    val bytes = Hex.decode("00" + maxHex).toOption.get
    assert(UInt256.fromBytes(bytes) == Left(BytesError.BadWidth(32, 33)), "one byte too many is not a word")
  }

  "toBytes" should "render zero as 32 zero bytes" in
    assert(Hex.encode(UInt256.Zero.toBytes) == "00" * UInt256.Width, "the fixed form is padded, not empty")

  "toMinimalBytes" should "render zero as no bytes at all" in
    assert(UInt256.Zero.toMinimalBytes.length == 0, "the scalar rule gives zero an empty encoding")

  "MaxValue" should "round-trip through the fixed-width form" in
    assert(Hex.encode(UInt256.MaxValue.toBytes) == maxHex, "the largest word is 32 bytes of 0xff")

  "a word" should "equal another built from a different path" in {
    val fromBytes = UInt256.fromBytes(Hex.decode("0186a0").toOption.get).toOption.get
    assert(UInt256.fromLong(100000L) == Right(fromBytes), "equality is by value, not by construction route")
  }

  it should "be found again as a Map key" in {
    val key = UInt256.fromLong(100000L).toOption.get
    val other = UInt256.fromBytes(Hex.decode("0186a0").toOption.get).toOption.get
    assert(Map(key -> "found").get(other).contains("found"), "an erased type that inherited array identity would miss")
  }

  it should "deduplicate in a Set" in {
    val a = UInt256.fromLong(1L).toOption.get
    val b = UInt256.fromBytes(Hex.decode("01").toOption.get).toOption.get
    assert(Set(a, b).size == 1, "two spellings of one value are one member")
  }

  "the Ordering" should "sort by numeric value without deadlocking on first use" in {
    val values = List(UInt256.MaxValue, UInt256.Zero, UInt256.fromLong(1L).toOption.get)
    assert(values.sorted.map(_.toBigInt) == List(BigInt(0), BigInt(1), BigInt(2).pow(256) - 1), "ascending by value")
  }
