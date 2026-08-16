package org.fukuii.bytes

/** An immutable byte string of no fixed width.
  *
  * ==Why this exists rather than `IArray[Byte]` in a domain type==
  *
  * `IArray` erases to an array and inherits its identity semantics, so a value
  * holding one compares by reference. A `case class` carrying an
  * `IArray[Byte]` therefore gets a generated `equals` that reports two
  * otherwise-identical values unequal, and the same value absent from a `Map`
  * that contains it. That is the hazard the fixed-width types are a
  * `final class` for; this is the same hazard where the width is not fixed.
  *
  * It is not a general-purpose buffer. It exists for the domain fields that are
  * genuinely arbitrary-length byte strings — a header's extra data, a
  * transaction's input, a log's data, an account's code.
  *
  * ==Defensive at both ends==
  *
  * `IArray` is immutable by type but [[IArray.unsafeFromArray]] can be handed a
  * array the caller still holds, so [[Bytes.fromArray]] copies on the way in.
  * [[toArray]] copies on the way out. [[toIArray]] does not, and does not need
  * to.
  */
final class Bytes private (private val raw: IArray[Byte]):

  def length: Int = raw.length

  def isEmpty: Boolean = raw.isEmpty

  def nonEmpty: Boolean = raw.nonEmpty

  def toIArray: IArray[Byte] = raw

  def toArray: Array[Byte] =
    val out = new Array[Byte](raw.length)
    var i = 0
    while i < raw.length do
      out(i) = raw(i)
      i += 1
    out

  def toHex: String = Hex.encode(raw)

  override def equals(that: Any): Boolean = that match
    case other: Bytes => FixedWidth.sameBytes(raw, other.raw)
    case _            => false

  override def hashCode(): Int = FixedWidth.hash(raw)

  /** Truncated past a length where printing the whole thing stops informing.
    *
    * A header's extra data is capped at 32 bytes by consensus rules and prints
    * whole; a transaction's input is not capped and routinely runs to
    * kilobytes, which is what this bound is for.
    */
  override def toString: String =
    if raw.length <= 32 then "0x" + Hex.encode(raw)
    else "0x" + Hex.encode(raw).take(64) + "…(" + raw.length + " bytes)"

object Bytes:

  val Empty: Bytes = new Bytes(IArray.empty)

  /** Takes ownership of an `IArray`, which is immutable by type. */
  def fromIArray(bytes: IArray[Byte]): Bytes = new Bytes(bytes)

  /** Copies, because the caller keeps a reference to a mutable array. */
  def fromArray(bytes: Array[Byte]): Bytes =
    new Bytes(IArray.unsafeFromArray(bytes.clone()))

  def fromHex(s: String): Either[BytesError, Bytes] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).map(new Bytes(_))
