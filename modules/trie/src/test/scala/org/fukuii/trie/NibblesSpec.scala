package org.fukuii.trie

import org.scalatest.flatspec.AnyFlatSpec

class NibblesSpec extends AnyFlatSpec:

  private def nibbles(values: Int*): Nibbles = Nibbles.fromValues(values).toOption.get

  "fromBytes" should "split each byte into its high nibble then its low nibble" in {
    val path = Nibbles.fromBytes(IArray(0xab.toByte, 0xcd.toByte))
    assert((0 until path.length).map(path.apply) == Seq(0xa, 0xb, 0xc, 0xd), "high nibble precedes low nibble")
  }

  it should "produce two nibbles for every input byte" in
    assert(Nibbles.fromBytes(IArray[Byte](1, 2, 3)).length == 6, "a key of n bytes is a path of 2n nibbles")

  it should "produce an empty path for an empty key" in
    assert(Nibbles.fromBytes(IArray.empty[Byte]).isEmpty, "an empty key has no nibbles")

  "toBytes" should "invert fromBytes" in {
    val original = IArray(0x00.toByte, 0x0f.toByte, 0xf0.toByte, 0xff.toByte)
    assert(Nibbles.fromBytes(original).toBytes.map(_.toHex).contains("000ff0ff"), "the byte string must survive")
  }

  it should "return nothing for an odd nibble count" in
    assert(nibbles(1, 2, 3).toBytes.isEmpty, "an odd path is not a whole number of bytes")

  "commonPrefixLength" should "count the leading nibbles two paths agree on" in
    assert(nibbles(1, 2, 3, 4).commonPrefixLength(nibbles(1, 2, 9)) == 2, "agreement stops at the third nibble")

  it should "be zero where the first nibble differs" in
    assert(nibbles(1, 2).commonPrefixLength(nibbles(2, 2)) == 0, "no leading nibble is shared")

  it should "stop at the shorter of the two paths" in
    assert(nibbles(1, 2).commonPrefixLength(nibbles(1, 2, 3)) == 2, "a prefix agrees for its whole length")

  it should "be zero against an empty path" in
    assert(nibbles(1).commonPrefixLength(Nibbles.Empty) == 0, "nothing is shared with an empty path")

  "take and drop" should "partition a path at the given nibble count" in {
    val path = nibbles(1, 2, 3, 4)
    assert(path.take(2) ++ path.drop(2) == path, "the two halves must recompose to the original")
  }

  "equality" should "compare paths by content rather than by identity" in
    assert(
      Nibbles.fromBytes(IArray(7.toByte)) == Nibbles.fromBytes(IArray(7.toByte)),
      "two equal paths must compare equal"
    )

  it should "make an equal path findable in a Map" in {
    val byPath = Map(Nibbles.fromBytes(IArray(7.toByte)) -> "value")
    assert(
      byPath.get(Nibbles.fromBytes(IArray(7.toByte))).contains("value"),
      "a path is a Map key throughout root computation"
    )
  }

  "single" should "reject a value outside a nibble's range" in
    assertThrows[IllegalArgumentException](Nibbles.single(16))

  "fromValues" should "reject a value outside a nibble's range" in
    assert(Nibbles.fromValues(Seq(1, 16)) == Left(TrieError.NibbleOutOfRange(16)), "16 is not a nibble")
