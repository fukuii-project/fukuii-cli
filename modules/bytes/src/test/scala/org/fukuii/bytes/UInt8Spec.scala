package org.fukuii.bytes

import org.scalatest.flatspec.AnyFlatSpec

/** The boundaries of the byte-wide unsigned integer.
  *
  * **This type is reached only through a transaction's `y_parity`, so every
  * vector that exercises it does so with a value the corpus considers well
  * formed or one it considers over-wide — and never through this type's own
  * surface.** Its constructors, its two byte forms and its pinned comparator had
  * no direct coverage at all.
  *
  * The comparator is the item worth stating a reason for. It is supplied
  * explicitly rather than found by implicit search, because inside this scope
  * the opaque type and its underlying `Int` are the same type, so a search could
  * resolve back to the instance being defined — a recursive lazy value whose
  * behavior the language reference calls undefined. A pin like that is only
  * known to work if something exercises it.
  */
class UInt8Spec extends AnyFlatSpec:

  "fromInt" should "accept the bottom of the range" in
    assert(UInt8.fromInt(0) == Right(UInt8.Zero), "zero is in range")

  it should "accept the top of the range" in
    assert(UInt8.fromInt(255) == Right(UInt8.MaxValue), "255 is the widest value a byte holds")

  it should "refuse the first value past the range" in
    assert(
      UInt8.fromInt(256) == Left(BytesError.OutOfRange),
      "256 is the corpus's own published rejection for a y_parity, so it must not construct"
    )

  it should "refuse a negative value" in
    assert(UInt8.fromInt(-1) == Left(BytesError.OutOfRange), "the type is unsigned")

  "fromBigInt" should "refuse a value past the range" in
    assert(UInt8.fromBigInt(BigInt(256)) == Left(BytesError.OutOfRange), "the same bound, from the wider type")

  "fromBytes" should "read the empty sequence as zero" in
    assert(
      UInt8.fromBytes(IArray.empty[Byte]) == Right(UInt8.Zero),
      "the scalar rule spells zero as the empty string rather than as a zero byte"
    )

  it should "refuse two bytes" in
    assert(
      UInt8.fromBytes(IArray(1.toByte, 2.toByte)) == Left(BytesError.BadWidth(UInt8.Width, 2)),
      "a second byte is a value this type cannot hold, not a wider spelling of one it can"
    )

  it should "read the high half of the range as a positive number" in
    assert(
      UInt8.fromBytes(IArray(0xff.toByte)).map(_.toInt) == Right(255),
      "the platform's byte is signed, and reading 0xff as -1 is the whole reason this type exists"
    )

  "fromHex" should "read a single byte" in
    assert(UInt8.fromHex("0x2a").map(_.toInt) == Right(42), "hex reaches the same constructor as bytes")

  "toBytes" should "be exactly one byte for zero" in
    assert(
      UInt8.Zero.toBytes.toSeq == Seq(0.toByte),
      "the fixed-width form keeps its width, which is what a fixed-width field encodes"
    )

  "toMinimalBytes" should "be empty for zero" in
    assert(
      UInt8.Zero.toMinimalBytes.toSeq == Seq.empty,
      "the scalar form drops a leading zero entirely, which is what RLP encodes a quantity as"
    )

  it should "be one byte for a non-zero value" in
    assert(UInt8.MaxValue.toMinimalBytes.toSeq == Seq(0xff.toByte), "nothing to trim above zero")

  /** The pin itself. If implicit search had resolved back to the instance being
    * defined, this is where it would hang or fail rather than compare.
    */
  "the pinned Ordering" should "sort the high half above the low" in
    assert(
      summon[Ordering[UInt8]].compare(UInt8.MaxValue, UInt8.Zero) > 0,
      "255 is above 0 unsigned, and would be below it if the comparison had gone signed"
    )
