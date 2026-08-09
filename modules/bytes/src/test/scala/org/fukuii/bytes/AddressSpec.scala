package org.fukuii.bytes

import org.scalatest.flatspec.AnyFlatSpec

/** The alignment behavior is go-ethereum's `Address.SetBytes`, `common/types.go`
  * @ `ethereum/go-ethereum, master, 7a1b1156 (2026-07-30)`: keep the rightmost bytes when
  * the input is too long, right-align when it is too short.
  *
  * The published address forms and the strict accept-and-reject cases are
  * tabulated in [[AddressPropSpec]].
  */
class AddressSpec extends AnyFlatSpec:

  private val Zero20 = IArray.from(Seq.fill(20)(0.toByte))

  "Address.fromBytes" should "reject an input shorter than twenty bytes" in {
    assert(Address.fromBytes(IArray(0x00.toByte)) == Left(BytesError.BadWidth(20, 1)), "one byte is not an address")
  }

  it should "reject an input longer than twenty bytes" in {
    val long = IArray.from(Seq.fill(21)(0.toByte))
    assert(Address.fromBytes(long) == Left(BytesError.BadWidth(20, 21)), "twenty-one bytes is not an address")
  }

  it should "accept exactly twenty bytes" in {
    assert(Address.fromBytes(Zero20).map(_.toHex) == Right("0" * 40), "the zero address renders as forty zeros")
  }

  "Address.fromBytesTruncating" should "keep the rightmost bytes when the input is too long" in {
    val overlong = IArray.from((1 to 24).map(_.toByte))
    val expected = IArray.from((5 to 24).map(_.toByte))
    assert(
      FixedWidth.sameBytes(Address.fromBytesTruncating(overlong).toBytes, expected),
      "the leftmost four bytes are dropped, not the rightmost"
    )
  }

  it should "left-pad with zero when the input is too short" in {
    assert(
      Address.fromBytesTruncating(IArray(0x10.toByte)).toHex == "0" * 38 + "10",
      "a short input is right-aligned, so its numeric value is preserved"
    )
  }

  "an Address" should "equal another built from the same bytes" in {
    assert(Address.fromBytesTruncating(Zero20) == Address.fromBytesTruncating(Zero20), "equality is by value")
  }

  it should "agree with an equal Address on hashCode" in {
    assert(
      Address.fromBytesTruncating(Zero20).hashCode() == Address.fromBytesTruncating(Zero20).hashCode(),
      "equal values must agree on hashCode or collections break"
    )
  }

  it should "find its entry when used as a Map key" in {
    val key = Address.fromBytesTruncating(Zero20)
    assert(Map(key -> 1).get(Address.fromBytesTruncating(Zero20)).contains(1), "a value-equal key must find the entry")
  }

  it should "deduplicate in a Set" in {
    val members = Set(Address.fromBytesTruncating(Zero20), Address.fromBytesTruncating(Zero20))
    assert(members.size == 1, "value-equal members must deduplicate")
  }

  it should "not equal a Hash carrying the same bytes" in {
    assert(
      !Address.fromBytesTruncating(Zero20).equals(Hash.fromBytesTruncating(Zero20)),
      "distinct types must not compare equal even when the bytes agree"
    )
  }
