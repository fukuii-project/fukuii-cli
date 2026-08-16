package org.fukuii.bytes

import org.scalatest.flatspec.AnyFlatSpec

/** Vectors are drawn from published sources rather than from this
  * implementation's own output: the 32-byte value is `Hash.EMPTY` in besu
  * `datatypes/src/test/java/org/hyperledger/besu/datatypes/HashTest.java`
  * @ `besu-eth/besu, main, fd8389c5 (2026-07-31)`.
  */
class HexSpec extends AnyFlatSpec:

  private val EmptyKeccakHex =
    "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"

  private def decoded(s: String): IArray[Byte] = Hex.decode(s).toOption.get

  "encode" should "produce lowercase with no prefix" in
    assert(Hex.encode(decoded(EmptyKeccakHex)) == EmptyKeccakHex, "encode is lowercase and unprefixed")

  it should "produce two characters per byte" in {
    val bytes = IArray(0x00.toByte, 0x0f.toByte, 0x7f.toByte, 0xff.toByte)
    assert(Hex.encode(bytes) == "000f7fff", "each byte is exactly two hex characters")
  }

  it should "render the empty input as the empty string" in
    assert(Hex.encode(IArray.empty[Byte]) == "", "no bytes means no characters")

  "decode" should "produce one byte per two characters" in
    assert(decoded(EmptyKeccakHex).length == 32, "64 characters decode to 32 bytes")

  it should "ignore a 0x prefix" in
    assert(
      Hex.encode(decoded("0x" + EmptyKeccakHex)) == EmptyKeccakHex,
      "the prefix must not change the decoded bytes"
    )

  it should "accept uppercase" in
    assert(
      Hex.encode(decoded(EmptyKeccakHex.toUpperCase)) == EmptyKeccakHex,
      "input case must not change the decoded bytes"
    )

  it should "reject an odd-length string" in
    assert(Hex.decode("abc") == Left(HexError.OddLength(3)), "an odd length cannot be whole bytes")

  it should "name the offending character and its position" in
    assert(Hex.decode("0xG0") == Left(HexError.NotHex('G', 0)), "the character and index are the useful part")

  it should "round-trip every byte value" in {
    val all = IArray.from((0 to 255).map(_.toByte))
    assert(FixedWidth.sameBytes(decoded(Hex.encode(all)), all), "every byte value survives a round trip")
  }
