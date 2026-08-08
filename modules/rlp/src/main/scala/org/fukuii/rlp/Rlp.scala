package org.fukuii.rlp

import scala.annotation.tailrec

/** An RLP item: either a byte array or a sequence of further items.
  *
  * The two cases are the Yellow Paper's `B` and `L`, and they are a disjoint
  * union rather than one type — the empty byte array and the empty sequence are
  * different values with different encodings (`0x80` and `0xc0`).
  *
  * Named `Sequence` rather than `List` because the specification's own word for
  * `L` is "sequence", and `List` would shadow the standard library's.
  */
sealed trait RlpItem

object RlpItem:

  /** A leaf byte array.
    *
    * NOT an `enum` case, and not a `case class`, because either would derive
    * equality from the `IArray` field — and `IArray` erases to `Array`, whose
    * equality is identity. Two items decoded from the same bytes would then
    * compare unequal, and neither would find the other in a `Map` or `Set`.
    */
  final class Bytes(val value: IArray[Byte]) extends RlpItem:
    override def equals(that: Any): Boolean = that match
      case other: Bytes => Bytes.sameBytes(value, other.value)
      case _            => false

    override def hashCode(): Int =
      var h = 1
      var i = 0
      while i < value.length do
        h = 31 * h + value(i)
        i += 1
      h

    override def toString: String = "RlpItem.Bytes(length=" + value.length + ")"

  object Bytes:
    def apply(value: IArray[Byte]): Bytes = new Bytes(value)

    def unapply(item: Bytes): Some[IArray[Byte]] = Some(item.value)

    private def sameBytes(a: IArray[Byte], b: IArray[Byte]): Boolean =
      if a.length != b.length then false
      else
        var i    = 0
        var same = true
        while i < a.length && same do
          if a(i) != b(i) then same = false
          i += 1
        same

  /** A sequence of further items. A `case class` is correct here: `Vector`
    * already compares structurally, and it compares its elements with their own
    * `equals`, which is the one defined above.
    */
  final case class Sequence(items: Vector[RlpItem]) extends RlpItem

/** A reason a byte sequence is not valid RLP.
  *
  * Several of these are canonicity failures rather than structural ones: the
  * bytes describe a well-formed item that is not the *only* encoding of its
  * value. RLP admits exactly one encoding per value, so a second one is invalid
  * input and not a variant to be accepted leniently.
  */
enum RlpError:
  case EmptyInput
  case Truncated(needed: Int, available: Int)
  case NonCanonicalSingleByte(value: Int)
  case NonOptimalLength(declared: Int)
  case LeadingZeroInLength
  case LengthTooLarge
  case TrailingBytes(count: Int)

object Rlp:

  private val SingleByteLimit  = 0x80
  private val ShortBytesMax    = 0xb7
  private val LongBytesMax     = 0xbf
  private val ShortSeqMax      = 0xf7
  private val ShortFormLimit   = 56

  def encode(item: RlpItem): IArray[Byte] = item match
    case RlpItem.Bytes(value)    => encodeBytes(value)
    case RlpItem.Sequence(items) => encodeSequence(items)

  /** Decodes exactly one item and requires it to consume the whole input.
    *
    * Trailing bytes are an error rather than a remainder: at a top-level
    * boundary they mean the input was not the single item it was taken to be,
    * and accepting them silently is how a truncated read becomes a valid-looking
    * value.
    */
  def decode(bytes: IArray[Byte]): Either[RlpError, RlpItem] =
    if bytes.isEmpty then Left(RlpError.EmptyInput)
    else
      decodeItem(bytes, 0).flatMap { (item, next) =>
        if next == bytes.length then Right(item)
        else Left(RlpError.TrailingBytes(bytes.length - next))
      }

  private def encodeBytes(x: IArray[Byte]): IArray[Byte] =
    if x.length == 1 && (x(0) & 0xff) < SingleByteLimit then x
    else if x.length < ShortFormLimit then IArray((0x80 + x.length).toByte) ++ x
    else
      val len = bigEndian(x.length)
      IArray((0xb7 + len.length).toByte) ++ len ++ x

  private def encodeSequence(items: Vector[RlpItem]): IArray[Byte] =
    val payload = items.foldLeft(IArray.empty[Byte])((acc, item) => acc ++ encode(item))
    if payload.length < ShortFormLimit then IArray((0xc0 + payload.length).toByte) ++ payload
    else
      val len = bigEndian(payload.length)
      IArray((0xf7 + len.length).toByte) ++ len ++ payload

  /** Minimal-length big-endian, so the leading byte is never zero. */
  private def bigEndian(n: Int): IArray[Byte] =
    @tailrec
    def go(rest: Int, acc: List[Byte]): List[Byte] =
      if rest == 0 then acc else go(rest >>> 8, (rest & 0xff).toByte :: acc)
    IArray.from(go(n, Nil))

  private def decodeItem(b: IArray[Byte], off: Int): Either[RlpError, (RlpItem, Int)] =
    if off >= b.length then Left(RlpError.Truncated(1, 0))
    else
      val prefix = b(off) & 0xff
      if prefix < SingleByteLimit then Right((RlpItem.Bytes(IArray(b(off))), off + 1))
      else if prefix <= ShortBytesMax then decodeShortBytes(b, off, prefix - 0x80)
      else if prefix <= LongBytesMax then decodeLongBytes(b, off, prefix - 0xb7)
      else if prefix <= ShortSeqMax then decodeShortSequence(b, off, prefix - 0xc0)
      else decodeLongSequence(b, off, prefix - 0xf7)

  private def decodeShortBytes(b: IArray[Byte], off: Int, len: Int): Either[RlpError, (RlpItem, Int)] =
    readBytes(b, off + 1, len).flatMap { payload =>
      // A lone byte below 0x80 encodes as itself, so the prefixed form of one
      // is a second encoding of the same value.
      if len == 1 && (payload(0) & 0xff) < SingleByteLimit then
        Left(RlpError.NonCanonicalSingleByte(payload(0) & 0xff))
      else Right((RlpItem.Bytes(payload), off + 1 + len))
    }

  private def decodeLongBytes(b: IArray[Byte], off: Int, lenOfLen: Int): Either[RlpError, (RlpItem, Int)] =
    readLength(b, off + 1, lenOfLen).flatMap { len =>
      if len < ShortFormLimit then Left(RlpError.NonOptimalLength(len))
      else
        readBytes(b, off + 1 + lenOfLen, len)
          .map(payload => (RlpItem.Bytes(payload), off + 1 + lenOfLen + len))
    }

  private def decodeShortSequence(b: IArray[Byte], off: Int, len: Int): Either[RlpError, (RlpItem, Int)] =
    decodeItems(b, off + 1, len).map(items => (RlpItem.Sequence(items), off + 1 + len))

  private def decodeLongSequence(b: IArray[Byte], off: Int, lenOfLen: Int): Either[RlpError, (RlpItem, Int)] =
    readLength(b, off + 1, lenOfLen).flatMap { len =>
      if len < ShortFormLimit then Left(RlpError.NonOptimalLength(len))
      else decodeItems(b, off + 1 + lenOfLen, len).map(items => (RlpItem.Sequence(items), off + 1 + lenOfLen + len))
    }

  private def decodeItems(b: IArray[Byte], off: Int, len: Int): Either[RlpError, Vector[RlpItem]] =
    if off.toLong + len > b.length then Left(RlpError.Truncated(len, (b.length - off).max(0)))
    else
      val end = off + len
      @tailrec
      def loop(pos: Int, acc: Vector[RlpItem]): Either[RlpError, Vector[RlpItem]] =
        if pos == end then Right(acc)
        else if pos > end then Left(RlpError.Truncated(len, pos - off))
        else
          decodeItem(b, pos) match
            case Left(e)             => Left(e)
            case Right((item, next)) => loop(next, acc :+ item)
      loop(off, Vector.empty)

  /** Reads a big-endian length, rejecting the forms that are not the one
    * canonical spelling of that number.
    */
  private def readLength(b: IArray[Byte], off: Int, n: Int): Either[RlpError, Int] =
    if off.toLong + n > b.length then Left(RlpError.Truncated(n, (b.length - off).max(0)))
    else if (b(off) & 0xff) == 0 then Left(RlpError.LeadingZeroInLength)
    // With no leading zero, five or more bytes always exceeds a signed 32-bit
    // length, so the width alone decides it and no accumulation can overflow.
    else if n > 4 then Left(RlpError.LengthTooLarge)
    else
      val value = (0 until n).foldLeft(0L)((acc, i) => (acc << 8) | (b(off + i) & 0xffL))
      if value > Int.MaxValue then Left(RlpError.LengthTooLarge) else Right(value.toInt)

  private def readBytes(b: IArray[Byte], off: Int, n: Int): Either[RlpError, IArray[Byte]] =
    if off.toLong + n > b.length then Left(RlpError.Truncated(n, (b.length - off).max(0)))
    else Right(b.slice(off, off + n))
