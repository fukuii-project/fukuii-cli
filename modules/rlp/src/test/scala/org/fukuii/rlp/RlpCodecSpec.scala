package org.fukuii.rlp

import org.fukuii.bytes.{Address, Hash, Hex, UInt256, UInt64}
import org.scalatest.flatspec.AnyFlatSpec

/** Behavior of the codec layer, and the demonstration that one value cannot
  * reach two encodings.
  *
  * The corpus-driven scalar table is in [[RlpCodecPropSpec]]; this file holds
  * the type-level obligations and the structural rejections.
  */
class RlpCodecSpec extends AnyFlatSpec:

  private def hashOf(hex: String): Hash       = Hash.fromHex(hex).toOption.get
  private def addressOf(hex: String): Address = Address.fromHex(hex).toOption.get
  private def word(n: Long): UInt256          = UInt256.fromLong(n).toOption.get

  private val aHash    = hashOf("00" * 31 + "ab")
  private val anAddress = addressOf("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed")

  "a byte string" should "round-trip through bytes" in {
    val value = Hex.decode("c0ffee").toOption.get
    assert(RlpCodec.decodeFrom[IArray[Byte]](RlpCodec.encodeTo(value)).map(Hex.encode) == Right("c0ffee"), "exact")
  }

  "a word" should "round-trip through bytes" in {
    assert(RlpCodec.decodeFrom[UInt256](RlpCodec.encodeTo(word(1000))) == Right(word(1000)), "exact")
  }

  it should "reject a sequence where a leaf was required" in {
    val bytes = Rlp.encode(RlpItem.Sequence(Vector.empty))
    assert(RlpCodec.decodeFrom[UInt256](bytes) == Left(RlpError.ExpectedBytes), "a list is not a quantity")
  }

  it should "reject a lone zero byte, which is not how zero is spelled" in {
    val bytes = Rlp.encode(RlpItem.Bytes(IArray(0.toByte)))
    assert(RlpCodec.decodeFrom[UInt256](bytes) == Left(RlpError.NonCanonicalScalar), "zero is the empty string")
  }

  it should "reject a payload wider than the type" in {
    val bytes = Rlp.encode(RlpItem.Bytes(IArray.from(Seq.fill(33)(0x01.toByte))))
    assert(RlpCodec.decodeFrom[UInt256](bytes) == Left(RlpError.WrongWidth(32, 33)), "33 bytes is not a word")
  }

  "a fixed-width value" should "keep its leading zeros, unlike a scalar" in {
    val encoded = Hex.encode(RlpCodec.encodeTo(aHash))
    assert(encoded == "a0" + "00" * 31 + "ab", "a hash is 32 bytes and its zeros are part of it")
  }

  it should "round-trip through bytes" in {
    assert(RlpCodec.decodeFrom[Hash](RlpCodec.encodeTo(aHash)) == Right(aHash), "exact")
  }

  it should "reject a short payload rather than left-padding it" in {
    val bytes = Rlp.encode(RlpItem.Bytes(IArray(0xab.toByte)))
    assert(RlpCodec.decodeFrom[Hash](bytes) == Left(RlpError.WrongWidth(32, 1)), "one byte is not a hash")
  }

  "an address" should "round-trip through bytes" in {
    assert(RlpCodec.decodeFrom[Address](RlpCodec.encodeTo(anAddress)) == Right(anAddress), "exact")
  }

  "a machine word" should "round-trip through bytes" in {
    val value = UInt64.fromLong(1000L).toOption.get
    assert(RlpCodec.decodeFrom[UInt64](RlpCodec.encodeTo(value)) == Right(value), "exact")
  }

  it should "spell zero as the empty string" in {
    assert(Hex.encode(RlpCodec.encodeTo(UInt64.Zero)) == "80", "the scalar rule gives zero an empty payload")
  }

  /** The value is not hypothetical: `ffffffffffffffff` appears as a withdrawal's
    * `validatorIndex` and again as its `index` in the corpus's block fixtures. A
    * signed reading of the same eight bytes is negative, which is why the word
    * this decodes into is unsigned.
    */
  it should "carry the whole unsigned range" in {
    val bytes = Hex.decode("88ffffffffffffffff").toOption.get
    assert(RlpCodec.decodeFrom[UInt64](bytes) == Right(UInt64.MaxValue), "the top of the range occurs in real blocks")
  }

  /** A gas limit at or above 2^63 is INVALID and still has to decode: the corpus
    * asserts `GASLIMIT_TOO_BIG` on a block, which is a judgment about a value it
    * expects a client to have read. A codec that could not represent it would
    * report the wrong failure from the wrong layer.
    */
  it should "decode a value the protocol forbids, leaving the judgment to consensus" in {
    val bytes = Hex.decode("888000000000000000").toOption.get
    assert(RlpCodec.decodeFrom[UInt64](bytes).map(_.toBigInt) == Right(BigInt(2).pow(63)), "decodable, not acceptable")
  }

  it should "reject a payload wider than the word" in {
    val bytes = Rlp.encode(RlpItem.Bytes(IArray.from(Seq.fill(9)(0x01.toByte))))
    assert(RlpCodec.decodeFrom[UInt64](bytes) == Left(RlpError.WrongWidth(8, 9)), "nine bytes is not a machine word")
  }

  "a sequence" should "round-trip element-wise" in {
    val values = Seq(aHash, hashOf("ff" * 32))
    assert(RlpCodec.decodeFrom[Seq[Hash]](RlpCodec.encodeTo(values)) == Right(values), "exact")
  }

  it should "reject a leaf where a list was required" in {
    val bytes = Rlp.encode(RlpItem.Bytes(IArray(0x01.toByte)))
    assert(RlpCodec.decodeFrom[Seq[Hash]](bytes) == Left(RlpError.ExpectedSequence), "a leaf is not a list")
  }

  it should "fail on the first bad element rather than dropping it" in {
    val bytes = Rlp.encode(RlpItem.Sequence(Vector(RlpItem.Bytes(IArray(0x01.toByte)))))
    assert(RlpCodec.decodeFrom[Seq[Hash]](bytes) == Left(RlpError.WrongWidth(32, 1)), "a bad element fails the whole")
  }

  // ───────────────────────── one value, one encoding ─────────────────────────
  //
  // The hazard: a byte string encodes as a single leaf, but a `Seq` encodes as
  // a list of per-element items. If one value could reach both instances it
  // would have two encodings, chosen by static type, with the compiler silent.
  //
  // The mechanism is the ABSENCE of `RlpCodec[Byte]`. Without it `Seq[Byte]`
  // cannot be summoned, so no byte sequence has a list-shaped encoding to be
  // confused with its leaf-shaped one.

  "a byte sequence" should "have no list-shaped encoding to be confused with" in {
    assertDoesNotCompile("summon[RlpCodec[Seq[Byte]]]")
  }

  it should "still have its leaf-shaped one, so the absence above is specific" in {
    assertCompiles("summon[RlpCodec[IArray[Byte]]]")
  }

  "the sequence codec" should "work for element types that have an instance" in {
    assertCompiles("summon[RlpCodec[Seq[Hash]]]")
  }

  it should "not fabricate an instance for an element type without one" in {
    assertDoesNotCompile("summon[RlpCodec[Seq[java.io.File]]]")
  }

  /** What this does NOT close, stated so the demonstration above is not read as
    * more than it is.
    *
    * Scala resolves a `given` in an enclosing lexical scope ahead of one in a
    * companion, so a caller who defines their own `RlpCodec[Hash]` in local
    * scope shadows the instance here rather than colliding with it. That is the
    * language's resolution order and no arrangement of this module changes it.
    * What is closed is the accidental case — two instances both reachable for
    * one value with nothing written to choose between them.
    */
  "a locally defined instance" should "shadow rather than collide, which is the residual" in {
    assertCompiles(
      """
      given RlpCodec[Hash] with
        def encode(value: Hash): RlpItem = RlpItem.Bytes(IArray.empty[Byte])
        def decode(item: RlpItem): Either[RlpError, Hash] = Left(RlpError.ExpectedBytes)
      summon[RlpCodec[Hash]]
      """
    )
  }
