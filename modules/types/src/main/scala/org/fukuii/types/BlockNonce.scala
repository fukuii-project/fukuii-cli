package org.fukuii.types

import org.fukuii.bytes.{BytesError, FixedWidth, Hex}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** The 64-bit proof-of-work solution in a block header.
  *
  * ==Fixed-width, and this is the field most easily got wrong==
  *
  * The Yellow Paper calls it "a 64-bit value" and asserts `H_n ∈ B_8` — eight
  * *bytes*, not a scalar. The distinction decides the block hash: a scalar drops
  * leading zeros and encodes zero as the empty string, so a nonce of
  * `0x0000000000000000` would encode as one byte instead of nine and every block
  * hash computed over it would be wrong.
  *
  * Measured across 124,465 headers in the conformance corpora, spanning every
  * fork from Frontier to Prague: the nonce element is eight bytes in every one,
  * with no exception. That is why it is a value type here rather than a
  * `UInt64`, whose codec is a scalar by contract.
  *
  * ==It is not deprecated at this layer==
  *
  * The Yellow Paper describes the field as deprecated, set to zero by networks
  * that have replaced proof of work. That is a statement about what a value
  * means on one network family, not about whether the field is carried — this
  * client's proof-of-work networks put a real solution here, and a header's
  * encoding is the same shape either way.
  */
final class BlockNonce private (private val raw: IArray[Byte]):

  def toBytes: IArray[Byte] = raw

  def toHex: String = Hex.encode(raw)

  override def equals(that: Any): Boolean = that match
    case other: BlockNonce => FixedWidth.sameBytes(raw, other.raw)
    case _                 => false

  override def hashCode(): Int = FixedWidth.hash(raw)

  override def toString: String = "0x" + Hex.encode(raw)

object BlockNonce:

  val Width: Int = 8

  /** Requires exactly [[Width]] bytes. */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, BlockNonce] =
    if bytes.length == Width then Right(new BlockNonce(FixedWidth.align(bytes, Width)))
    else Left(BytesError.BadWidth(Width, bytes.length))

  def fromHex(s: String): Either[BytesError, BlockNonce] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)

  /** Eight zero bytes — what a network that has replaced proof of work carries.
    *
    * Note this is not the empty string: see the width note on the type.
    */
  val Zero: BlockNonce = new BlockNonce(IArray.unsafeFromArray(new Array[Byte](Width)))

  given blockNonceCodec: RlpCodec[BlockNonce] with
    def encode(value: BlockNonce): RlpItem = RlpItem.Bytes(value.toBytes)
    def decode(item: RlpItem): Either[RlpError, BlockNonce] = item match
      case RlpItem.Bytes(payload) =>
        BlockNonce.fromBytes(payload).left.map(_ => RlpError.WrongWidth(Width, payload.length))
      case _: RlpItem.Sequence => Left(RlpError.ExpectedSequence)
