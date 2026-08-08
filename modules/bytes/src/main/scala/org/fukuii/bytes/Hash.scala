package org.fukuii.bytes

/** A 32-byte value.
  *
  * Distinct from [[Address]] at the type level even though both wrap bytes, so
  * that passing one where the other is expected cannot compile.
  */
final class Hash private (private val raw: IArray[Byte]):

  def toBytes: IArray[Byte] = raw

  def toHex: String = Hex.encode(raw)

  override def equals(that: Any): Boolean = that match
    case other: Hash => FixedWidth.sameBytes(raw, other.raw)
    case _           => false

  override def hashCode(): Int = FixedWidth.hash(raw)

  override def toString: String = "0x" + Hex.encode(raw)

object Hash:

  val Width: Int = 32

  /** Requires exactly [[Width]] bytes. */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, Hash] =
    if bytes.length == Width then Right(new Hash(FixedWidth.align(bytes, Width)))
    else Left(BytesError.BadWidth(Width, bytes.length))

  /** Keeps the rightmost [[Width]] bytes, left-padding with zero when short.
    *
    * Separate from [[fromBytes]] so that accepting a wrong-width input is
    * something a call site opts into visibly, rather than the default.
    */
  def fromBytesTruncating(bytes: IArray[Byte]): Hash =
    new Hash(FixedWidth.align(bytes, Width))

  def fromHex(s: String): Either[BytesError, Hash] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)
