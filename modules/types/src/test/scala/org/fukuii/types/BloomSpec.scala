package org.fukuii.types

import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** What a bloom must refuse, and the one property its width buys.
  *
  * [[BloomPropSpec]] pins what `M(O)` computes. This pins the encoding
  * boundary, which had no test of its own — the bloom's codec was reached only
  * incidentally, through a block header carrying an empty one.
  */
class BloomSpec extends AnyFlatSpec:

  "a bloom" should "encode as a fixed 256 bytes, leading zeros included" in {
    assert(
      RlpCodec[Bloom].encode(Bloom.Empty) == RlpItem.Bytes(IArray.fill(Bloom.Width)(0.toByte)),
      "a bloom is fixed-width, so an empty one is 256 zero bytes and never the empty string a scalar zero would take"
    )
  }

  /** The mirror of the width check, and the reason a bloom cannot be a scalar:
    * the three-byte prefix plus 256 bytes is what a header's own encoding
    * budgets for.
    */
  it should "occupy 259 octets once its length prefix is written" in {
    assert(
      Rlp.encode(RlpCodec[Bloom].encode(Bloom.Empty)).length == 259,
      "0xb9 0x0100 and 256 bytes"
    )
  }

  "a bloom of the wrong width" should "be refused rather than padded" in {
    assert(
      RlpCodec[Bloom].decode(RlpItem.Bytes(IArray.fill(255)(0.toByte)))
        == Left(RlpError.WrongWidth(Bloom.Width, 255)),
      "255 bytes is not a short bloom, it is not a bloom"
    )
  }

  /** Reporting `ExpectedSequence` here names what the decoder was handed rather
    * than what it wanted, which is backwards and was the reported reason until
    * this test existed.
    */
  "a list where a bloom is expected" should "be refused as not being a byte string" in {
    assert(
      RlpCodec[Bloom].decode(RlpItem.Sequence(Vector.empty)) == Left(RlpError.ExpectedBytes),
      "a bloom is a byte string, and the reason must name what was expected"
    )
  }

  "the empty bloom" should "round-trip through its own encoding" in {
    val bytes = RlpCodec.encodeTo(Bloom.Empty)
    assert(
      RlpCodec.decodeFrom[Bloom](bytes) == Right(Bloom.Empty),
      "encode and decode are separate obligations and must agree"
    )
  }
