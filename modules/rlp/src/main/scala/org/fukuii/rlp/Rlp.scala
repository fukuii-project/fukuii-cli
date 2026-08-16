package org.fukuii.rlp

import org.fukuii.bytes.FixedWidth

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
      case other: Bytes => FixedWidth.sameBytes(value, other.value)
      case _            => false

    /** Delegated rather than reimplemented: these are `Map` keys decoded from a
      * peer's bytes, so the avalanche is a defense and not a detail, and a
      * second copy of it is the one thing
      * [[org.fukuii.bytes.FixedWidth]] was widened across the project to
      * prevent. The reasoning lives with it.
      */
    override def hashCode(): Int = FixedWidth.hash(value)

    override def toString: String = "RlpItem.Bytes(length=" + value.length + ")"

  object Bytes:
    def apply(value: IArray[Byte]): Bytes = new Bytes(value)

    def unapply(item: Bytes): Some[IArray[Byte]] = Some(item.value)

  /** A sequence of further items. A `case class` is correct here: `Vector`
    * already compares structurally, and it compares its elements with their own
    * `equals`, which is the one defined above.
    */
  final case class Sequence(items: Vector[RlpItem]) extends RlpItem

/** A reason a byte sequence is not valid RLP, or is not a valid value of the
  * type a codec was asked for.
  *
  * Several of these are canonicity failures rather than structural ones: the
  * bytes describe a well-formed item that is not the *only* encoding of its
  * value. RLP admits exactly one encoding per value, so a second one is invalid
  * input and not a variant to be accepted leniently.
  *
  * The two groups are separated below because they answer different questions.
  * The first is *are these bytes RLP at all*, decided without knowing what the
  * caller wanted. The second is *are these well-formed items the value that was
  * asked for*, which only a codec can decide. One channel carries both so that
  * a decode composes through a single `flatMap`.
  */
enum RlpError:
  // Structural: the bytes are not RLP.
  case EmptyInput
  case Truncated(needed: Int, available: Int)
  case NonCanonicalSingleByte(value: Int)
  case NonOptimalLength(declared: Int)
  case LeadingZeroInLength
  case LengthTooLarge
  case TrailingBytes(count: Int)
  case NestingTooDeep(limit: Int)

  // Typed: the items are RLP, and are not this value.
  case ExpectedBytes
  case ExpectedSequence
  case NonCanonicalScalar

  /** A leaf's BYTE count is not the width the value requires.
    *
    * Distinct from [[WrongArity]], and the pair exists for the reason
    * [[ExpectedBytes]] and [[ExpectedSequence]] are two cases rather than one:
    * an error names what was WANTED, and "width" and "arity" want different
    * things. `WrongWidth(32, 31)` is a 31-byte leaf where a 32-byte value was
    * required; `WrongArity(15, 14)` is a 14-element sequence where 15 elements
    * were required. One case for both made those two indistinguishable at the
    * call site without knowing which decoder produced them.
    */
  case WrongWidth(expected: Int, actual: Int)

  /** A sequence's ELEMENT count is not the arity the value requires. See
    * [[WrongWidth]] for why the two are separate.
    */
  case WrongArity(expected: Int, actual: Int)

  /** A tagged union's tag is not one the codec knows.
    *
    * Deliberately named for the encoding concept rather than for any type that
    * uses it: this module must not learn what a transaction is. A codec over a
    * tagged union reports the tag it could not place, and the tag's meaning
    * stays with the type that defines it.
    */
  case UnknownDiscriminant(value: Int)

object Rlp:

  private val SingleByteLimit = 0x80
  private val ShortBytesMax = 0xb7
  private val LongBytesMax = 0xbf
  private val ShortSeqMax = 0xf7
  private val ShortFormLimit = 56

  /** The deepest nesting `decode` will follow before rejecting the input.
    *
    * Decoding recurses through the JVM stack, and one nesting level costs a
    * single byte to encode — so without a bound, a payload of a few kilobytes
    * reaches a `StackOverflowError`, which is not catchable as a decode failure
    * and takes the thread with it. That is a denial of service on any input a
    * peer can send, and this codec's whole purpose is parsing bytes that
    * arrive from one.
    *
    * THE VALUE IS CALIBRATED TO THE SMALLEST STACK THIS CAN RUN ON, NOT TO A
    * COMFORTABLE ONE. Measured on the pinned toolchain, one fresh JVM per row,
    * with the bound lifted so the real limit shows:
    *
    *   - 1 MB stack (the JVM's own default), frames INTERPRETED — overflow at
    *     depth ~904. A bound of 1024 would never fire there.
    *   - 1 MB stack, once the decode path is JIT-compiled — past 3000.
    *
    * The interpreted figure is the one that binds: a handler thread decodes its
    * first payloads before anything is compiled, which is exactly when a peer's
    * opening message arrives. A bound tuned to the warm case is a bound that
    * fails on precisely the input it exists to stop.
    *
    * 512 clears the interpreted 1 MB case with room to spare and is still two
    * orders of magnitude above anything the protocol's own structures need,
    * which is single digits. Raising it re-opens the coupling to `-Xss`, so
    * re-measure interpreted rather than reasoning from the warm number.
    */
  val MaxNestingDepth = 512

  def encode(item: RlpItem): IArray[Byte] = item match
    case RlpItem.Bytes(value)    => encodeBytes(value)
    case RlpItem.Sequence(items) => encodeSequence(items)

  /** Decodes exactly one item and requires it to consume the whole input.
    *
    * Trailing bytes are an error rather than a remainder: at a top-level
    * boundary they mean the input was not the single item it was taken to be,
    * and accepting them silently is how a truncated read becomes a valid-looking
    * value.
    *
    * ==There is no size or item budget here, and one is owed==
    *
    * Nesting is bounded; total size is not. Measured against this codec: a
    * long-form sequence whose payload is many one-byte items retains **20 to 45
    * times** the input in heap, and every such input is valid, canonical RLP
    * that returns `Right`. At a 16 MB frame that is hundreds of megabytes of
    * live heap from a single message. The cost is per-item object overhead, so
    * it is structural rather than a leak, and no bounds check reaches it —
    * every read is already checked before allocating.
    *
    * **Whoever wires the first decode path that takes bytes from a peer or an
    * RPC caller owns closing this**, with a maximum input length, a maximum
    * item count, or both, applied here rather than left to each caller. It is
    * deliberately not chosen now: the value belongs with the transport that
    * sets a frame size, and picking one without that is a number with no
    * evidence behind it.
    */
  def decode(bytes: IArray[Byte]): Either[RlpError, RlpItem] =
    if bytes.isEmpty then Left(RlpError.EmptyInput)
    else
      decodeItem(bytes, 0, 0).flatMap { (item, next) =>
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
    // Concatenate once, not once per element. Folding with `++` reallocates the
    // whole accumulator on every item, which is quadratic in the number of
    // siblings — and a transaction list is hundreds of them, at every nesting
    // level. The decode side already accumulates in amortized constant time.
    val payload = concatAll(items.map(encode))
    if payload.length < ShortFormLimit then IArray((0xc0 + payload.length).toByte) ++ payload
    else
      val len = bigEndian(payload.length)
      IArray((0xf7 + len.length).toByte) ++ len ++ payload

  /** The running total accumulates in a `Long` and is checked once.
    *
    * Summing into an `Int` wraps negative somewhere past two gigabytes of
    * output and reaches `new Array[Byte]` as a negative size, which surfaces as
    * a `NegativeArraySizeException` naming nothing. The result is
    * unrepresentable at that size whatever this does — a JVM array is indexed
    * by `Int` — so the failure is not avoidable; what is avoidable is its being
    * unintelligible.
    */
  private def concatAll(parts: Vector[IArray[Byte]]): IArray[Byte] =
    var total = 0L
    var i = 0
    while i < parts.length do
      total += parts(i).length.toLong
      i += 1
    require(total <= Int.MaxValue, "RLP payload exceeds the largest representable byte array")
    val out = new Array[Byte](total.toInt)
    var pos = 0
    i = 0
    while i < parts.length do
      val part = parts(i)
      var j = 0
      while j < part.length do
        out(pos + j) = part(j)
        j += 1
      pos += part.length
      i += 1
    IArray.unsafeFromArray(out)

  /** Minimal-length big-endian, so the leading byte is never zero. */
  private def bigEndian(n: Int): IArray[Byte] =
    @tailrec
    def go(rest: Int, acc: List[Byte]): List[Byte] =
      if rest == 0 then acc else go(rest >>> 8, (rest & 0xff).toByte :: acc)
    IArray.from(go(n, Nil))

  private def decodeItem(b: IArray[Byte], off: Int, depth: Int): Either[RlpError, (RlpItem, Int)] =
    if off >= b.length then Left(RlpError.Truncated(1, 0))
    else
      val prefix = b(off) & 0xff
      if prefix < SingleByteLimit then Right((RlpItem.Bytes(IArray(b(off))), off + 1))
      else if prefix <= ShortBytesMax then decodeShortBytes(b, off, prefix - 0x80)
      else if prefix <= LongBytesMax then decodeLongBytes(b, off, prefix - 0xb7)
      else if prefix <= ShortSeqMax then decodeShortSequence(b, off, prefix - 0xc0, depth)
      else decodeLongSequence(b, off, prefix - 0xf7, depth)

  private def decodeShortBytes(b: IArray[Byte], off: Int, len: Int): Either[RlpError, (RlpItem, Int)] =
    readBytes(b, off + 1, len).flatMap { payload =>
      // A lone byte below 0x80 encodes as itself, so the prefixed form of one
      // is a second encoding of the same value.
      if len == 1 && (payload(0) & 0xff) < SingleByteLimit then Left(RlpError.NonCanonicalSingleByte(payload(0) & 0xff))
      else Right((RlpItem.Bytes(payload), off + 1 + len))
    }

  private def decodeLongBytes(b: IArray[Byte], off: Int, lenOfLen: Int): Either[RlpError, (RlpItem, Int)] =
    readLength(b, off + 1, lenOfLen).flatMap { len =>
      if len < ShortFormLimit then Left(RlpError.NonOptimalLength(len))
      else
        readBytes(b, off + 1 + lenOfLen, len)
          .map(payload => (RlpItem.Bytes(payload), off + 1 + lenOfLen + len))
    }

  private def decodeShortSequence(b: IArray[Byte], off: Int, len: Int, depth: Int): Either[RlpError, (RlpItem, Int)] =
    decodeItems(b, off + 1, len, depth).map(items => (RlpItem.Sequence(items), off + 1 + len))

  private def decodeLongSequence(
      b: IArray[Byte],
      off: Int,
      lenOfLen: Int,
      depth: Int
  ): Either[RlpError, (RlpItem, Int)] =
    readLength(b, off + 1, lenOfLen).flatMap { len =>
      if len < ShortFormLimit then Left(RlpError.NonOptimalLength(len))
      else
        decodeItems(b, off + 1 + lenOfLen, len, depth).map(items => (RlpItem.Sequence(items), off + 1 + lenOfLen + len))
    }

  private def decodeItems(b: IArray[Byte], off: Int, len: Int, depth: Int): Either[RlpError, Vector[RlpItem]] =
    if depth >= MaxNestingDepth then Left(RlpError.NestingTooDeep(MaxNestingDepth))
    else if off.toLong + len > b.length then Left(RlpError.Truncated(len, (b.length - off).max(0)))
    else
      val end = off + len
      @tailrec
      def loop(pos: Int, acc: Vector[RlpItem]): Either[RlpError, Vector[RlpItem]] =
        if pos == end then Right(acc)
        else if pos > end then Left(RlpError.Truncated(len, pos - off))
        else
          decodeItem(b, pos, depth + 1) match
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
