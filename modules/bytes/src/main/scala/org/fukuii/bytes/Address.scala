package org.fukuii.bytes

/** A 20-byte account address.
  *
  * Rendering an address in its mixed-case checksummed form is deliberately not
  * here: that form is defined over the keccak-256 digest of the lowercase hex,
  * and the digest belongs to a module that builds on this one.
  */
final class Address private (private val raw: IArray[Byte]):

  def toBytes: IArray[Byte] = raw

  def toHex: String = Hex.encode(raw)

  override def equals(that: Any): Boolean = that match
    case other: Address => FixedWidth.sameBytes(raw, other.raw)
    case _              => false

  override def hashCode(): Int = FixedWidth.hash(raw)

  override def toString: String = "0x" + Hex.encode(raw)

object Address:

  val Width: Int = 20

  /** Requires exactly [[Width]] bytes. */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, Address] =
    if bytes.length == Width then Right(new Address(FixedWidth.align(bytes, Width)))
    else Left(BytesError.BadWidth(Width, bytes.length))

  /** Keeps the rightmost [[Width]] bytes, left-padding with zero when short.
    *
    * Separate from [[fromBytes]] so that accepting a wrong-width input is
    * something a call site opts into visibly, rather than the default.
    */
  def fromBytesTruncating(bytes: IArray[Byte]): Address =
    new Address(FixedWidth.align(bytes, Width))

  def fromHex(s: String): Either[BytesError, Address] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)
