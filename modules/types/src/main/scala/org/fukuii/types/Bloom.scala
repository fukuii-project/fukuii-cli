package org.fukuii.types

import org.fukuii.bytes.{BytesError, FixedWidth, Hex}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** The 2048-bit Bloom filter over the indexable information — logger address
  * and log topics — in a block's receipts.
  *
  * 2048 bits is 256 bytes, and the Yellow Paper states the width in bits at the
  * point it asserts the receipt's own field, so the byte figure is derived
  * rather than quoted.
  *
  * ==Value semantics only, and the omission is deliberate==
  *
  * This carries a bloom; it does not construct one. The construction is
  * `M(O)`, three bits per entry taken from the low-order 11 bits of each of the
  * first three 16-bit groups of a Keccak-256 hash — so it needs both a log type
  * to read and a digest to call, and it lands with the log type rather than
  * ahead of it. Building it here would mean choosing what a log looks like from
  * this side of the boundary.
  */
final class Bloom private (private val raw: IArray[Byte]):

  def toBytes: IArray[Byte] = raw

  def toHex: String = Hex.encode(raw)

  override def equals(that: Any): Boolean = that match
    case other: Bloom => FixedWidth.sameBytes(raw, other.raw)
    case _            => false

  override def hashCode(): Int = FixedWidth.hash(raw)

  /** Truncated on both ends. A full bloom is 512 hex characters and is almost
    * always mostly zero, so printing it whole buries whatever line carries it.
    */
  override def toString: String =
    val hex = Hex.encode(raw)
    "0x" + hex.take(8) + "…" + hex.takeRight(8)

object Bloom:

  val Width: Int = 256

  /** Requires exactly [[Width]] bytes. */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, Bloom] =
    if bytes.length == Width then Right(new Bloom(FixedWidth.align(bytes, Width)))
    else Left(BytesError.BadWidth(Width, bytes.length))

  def fromHex(s: String): Either[BytesError, Bloom] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)

  /** All bits clear — the bloom of a block whose receipts carry no log. */
  val Empty: Bloom = new Bloom(IArray.unsafeFromArray(new Array[Byte](Width)))

  /** Fixed-width, so its leading zeros are part of it: an empty bloom is 256
    * zero bytes and encodes as such, never as the empty string a scalar zero
    * would take.
    */
  given bloomCodec: RlpCodec[Bloom] with
    def encode(value: Bloom): RlpItem = RlpItem.Bytes(value.toBytes)
    def decode(item: RlpItem): Either[RlpError, Bloom] = item match
      case RlpItem.Bytes(payload) =>
        Bloom.fromBytes(payload).left.map(_ => RlpError.WrongWidth(Width, payload.length))
      case _: RlpItem.Sequence => Left(RlpError.ExpectedSequence)
