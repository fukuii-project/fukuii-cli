package org.fukuii.types

import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** What a nonce must refuse, and the width its encoding cannot negotiate.
  *
  * The nonce's codec had no spec of its own: it was reached only through a
  * header carrying one, which exercises the accepting direction and never the
  * refusing one. Both boundaries below were unread until this file existed.
  */
class BlockNonceSpec extends AnyFlatSpec:

  "a nonce" should "encode as a fixed eight bytes, leading zeros included" in {
    assert(
      Rlp.encode(RlpCodec[BlockNonce].encode(BlockNonce.Zero)).length == 9,
      "0x88 and eight bytes — a scalar would collapse zero to the empty string and change every block hash"
    )
  }

  it should "round-trip through its own encoding" in {
    val bytes = RlpCodec.encodeTo(BlockNonce.Zero)
    assert(
      RlpCodec.decodeFrom[BlockNonce](bytes) == Right(BlockNonce.Zero),
      "encode and decode are separate obligations and must agree"
    )
  }

  "a nonce of the wrong width" should "be refused rather than padded" in {
    assert(
      RlpCodec[BlockNonce].decode(RlpItem.Bytes(IArray.fill(7)(0.toByte)))
        == Left(RlpError.WrongWidth(BlockNonce.Width, 7)),
      "seven bytes is not a short nonce, it is not a nonce"
    )
  }

  /** Reporting `ExpectedSequence` here names what the decoder was handed rather
    * than what it wanted. Every other leaf instance in the codebase names what
    * it wanted; this one disagreed, and nothing read it.
    */
  "a list where a nonce is expected" should "be refused as not being a byte string" in {
    assert(
      RlpCodec[BlockNonce].decode(RlpItem.Sequence(Vector.empty)) == Left(RlpError.ExpectedBytes),
      "a nonce is a byte string, and the reason must name what was expected"
    )
  }
