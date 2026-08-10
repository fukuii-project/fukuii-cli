package org.fukuii.bytes

import org.scalatest.flatspec.AnyFlatSpec

/** The 32-byte values are published constants, taken from besu
  * `datatypes/src/test/java/org/hyperledger/besu/datatypes/HashTest.java`
  * @ `besu-eth/besu, main, fd8389c5 (2026-07-31)`. They are used here only as
  * well-known 32-byte sequences: this module computes no digest, so nothing
  * below asserts that either value is the digest of anything.
  */
class HashSpec extends AnyFlatSpec:

  private val EmptyKeccak =
    "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"

  private val EmptyRequests =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

  "Hash.fromHex" should "round-trip a published 32-byte value" in {
    assert(Hash.fromHex(EmptyKeccak).map(_.toHex) == Right(EmptyKeccak), "round trip must be exact")
  }

  it should "distinguish two different published values" in {
    assert(Hash.fromHex(EmptyKeccak) != Hash.fromHex(EmptyRequests), "different bytes must not compare equal")
  }

  "Hash.fromBytes" should "reject an address-width input" in {
    val twenty = IArray.from(Seq.fill(20)(0.toByte))
    assert(Hash.fromBytes(twenty) == Left(BytesError.BadWidth(32, 20)), "twenty bytes is not a hash")
  }

  it should "reject an input longer than thirty-two bytes" in {
    val long = IArray.from(Seq.fill(33)(0.toByte))
    assert(Hash.fromBytes(long) == Left(BytesError.BadWidth(32, 33)), "thirty-three bytes is not a hash")
  }

  "Hash.fromBytesTruncating" should "keep the rightmost bytes when the input is too long" in {
    val overlong = IArray.from((1 to 40).map(_.toByte))
    val expected = IArray.from((9 to 40).map(_.toByte))
    assert(
      FixedWidth.sameBytes(Hash.fromBytesTruncating(overlong).toBytes, expected),
      "the leftmost eight bytes are dropped"
    )
  }

  it should "left-pad with zero when the input is too short" in {
    assert(Hash.fromBytesTruncating(IArray(0x10.toByte)).toHex == "0" * 62 + "10", "a short input is right-aligned")
  }

  "a Hash" should "find its entry when used as a Map key" in {
    val key = Hash.fromHex(EmptyKeccak).toOption.get
    val other = Hash.fromHex(EmptyKeccak).toOption.get
    assert(Map(key -> 1).get(other).contains(1), "a value-equal key must find the entry")
  }

  it should "deduplicate in a Set" in {
    val a = Hash.fromHex(EmptyKeccak).toOption.get
    val b = Hash.fromHex(EmptyKeccak).toOption.get
    assert(Set(a, b).size == 1, "value-equal members must deduplicate")
  }

  it should "render with a 0x prefix in toString" in {
    assert(Hash.fromHex(EmptyKeccak).map(_.toString) == Right("0x" + EmptyKeccak), "toString is the prefixed form")
  }

  it should "render without a prefix in toHex" in {
    assert(Hash.fromHex(EmptyKeccak).map(_.toHex) == Right(EmptyKeccak), "toHex is the bare form")
  }

  /** The pair is constructed, not searched for. Under an accumulator of the form
    * `31 * h + b` any two inputs agreeing on a prefix and then differing by
    * `(+1, -31)` in two adjacent bytes produce the same code, because the
    * accumulator is linear: `31 * (31*h + 0x00) + 0x1f` and
    * `31 * (31*h + 0x01) + 0x00` are both `961*h + 31`.
    *
    * These are `Map` keys an attacker supplies, and Scala's CHAMP hash map keeps
    * colliding keys in a linear list, so a cheap supply of collisions is
    * quadratic work. A hash code that avalanches separates this pair; one that
    * merely accumulates cannot.
    */
  it should "not collide on a pair a linear accumulator cannot separate" in {
    val prefix = "00" * 30
    val a      = Hash.fromHex(prefix + "001f").toOption.get
    val b      = Hash.fromHex(prefix + "0100").toOption.get
    assert(a.hashCode() != b.hashCode(), "these differ, so their hash codes must differ too")
  }
