package org.fukuii.trie

import org.fukuii.bytes.{Bytes, FixedWidth}

/** A key path expressed as half-bytes, each holding a value from `0` to `15`
  * inclusive.
  *
  * A trie key arrives as a byte string and is traversed a nibble at a time,
  * because a branch node has sixteen children. The nibble sequence is therefore
  * the trie's own view of a key, and it is a distinct type from
  * [[org.fukuii.bytes.Bytes]] so that a nibble sequence cannot be handed to
  * anything expecting the byte string it came from — the two have different
  * lengths and different meanings, and neither conversion is total in both
  * directions.
  *
  * ==A `final class` and not an opaque type==
  *
  * The backing `IArray` erases to an array, whose equality is identity, so an
  * opaque wrapper would report two paths decoded from the same bytes unequal and
  * would lose them in a `Map`. This type is a `Map` key throughout root
  * computation, which is precisely where that fails. The explicit `equals` and
  * `hashCode` below are the same remedy
  * [[org.fukuii.bytes.Bytes]] applies for the same reason.
  */
final class Nibbles private (private val raw: IArray[Byte]):

  def length: Int = raw.length

  def isEmpty: Boolean = raw.isEmpty

  def nonEmpty: Boolean = raw.nonEmpty

  /** The nibble at `index`, as a value from `0` to `15`. */
  def apply(index: Int): Int = raw(index).toInt

  def take(count: Int): Nibbles = new Nibbles(raw.slice(0, count.max(0).min(raw.length)))

  def drop(count: Int): Nibbles = new Nibbles(raw.slice(count.max(0).min(raw.length), raw.length))

  def ++(that: Nibbles): Nibbles = new Nibbles(raw ++ that.raw)

  /** The length of the longest prefix this shares with `that`. */
  def commonPrefixLength(that: Nibbles): Int =
    val limit = raw.length.min(that.raw.length)
    var i = 0
    while i < limit && raw(i) == that.raw(i) do i += 1
    i

  /** Hex-prefix encoding: this path packed two nibbles to a byte behind a single
    * flag nibble.
    *
    * The flag nibble's lowest bit records whether the nibble count is odd and
    * its second-lowest records `isLeaf`; the two high bits are unused and are
    * emitted as zero. When the count is odd the first nibble shares the flag
    * byte, which is what leaves an even number to pair off.
    */
  def toCompact(isLeaf: Boolean): IArray[Byte] =
    val flagBase = if isLeaf then 2 else 0
    val odd = (raw.length & 1) == 1
    val out = new Array[Byte](raw.length / 2 + 1)
    var source = 0
    if odd then
      out(0) = (16 * (flagBase + 1) + raw(0)).toByte
      source = 1
    else out(0) = (16 * flagBase).toByte
    var target = 1
    while source < raw.length do
      out(target) = (16 * raw(source) + raw(source + 1)).toByte
      source += 2
      target += 1
    IArray.unsafeFromArray(out)

  /** The byte string this path came from, or nothing when the nibble count is
    * odd. Every trie key is a whole number of bytes, so an odd count is a path
    * that no key produced.
    */
  def toBytes: Option[Bytes] =
    if (raw.length & 1) == 1 then None
    else
      val out = new Array[Byte](raw.length / 2)
      var i = 0
      while i < out.length do
        out(i) = (16 * raw(2 * i) + raw(2 * i + 1)).toByte
        i += 1
      Some(Bytes.fromIArray(IArray.unsafeFromArray(out)))

  override def equals(that: Any): Boolean = that match
    case other: Nibbles => FixedWidth.sameBytes(raw, other.raw)
    case _              => false

  override def hashCode(): Int = FixedWidth.hash(raw)

  override def toString: String =
    val out = new StringBuilder("Nibbles(")
    var i = 0
    while i < raw.length do
      val _ = out.append(Character.forDigit(raw(i).toInt, Nibbles.Radix))
      i += 1
    out.append(')').toString

object Nibbles:

  /** The number of children a branch node has, and so the number of distinct
    * nibble values.
    */
  val Radix: Int = 16

  val Empty: Nibbles = new Nibbles(IArray.empty[Byte])

  /** Splits each byte into its high nibble then its low nibble, so a key of `n`
    * bytes becomes a path of `2n` nibbles.
    */
  def fromBytes(bytes: IArray[Byte]): Nibbles =
    val out = new Array[Byte](2 * bytes.length)
    var i = 0
    while i < bytes.length do
      out(2 * i) = ((bytes(i) & 0xf0) >>> 4).toByte
      out(2 * i + 1) = (bytes(i) & 0x0f).toByte
      i += 1
    new Nibbles(IArray.unsafeFromArray(out))

  /** A one-nibble path, for the branch index consumed on the way into a child. */
  def single(nibble: Int): Nibbles =
    require(nibble >= 0 && nibble < Radix, "a nibble is between 0 and 15 inclusive")
    new Nibbles(IArray(nibble.toByte))

  def fromValues(values: Seq[Int]): Either[TrieError, Nibbles] =
    values.find(v => v < 0 || v >= Radix) match
      case Some(bad) => Left(TrieError.NibbleOutOfRange(bad))
      case None      => Right(new Nibbles(IArray.from(values.map(_.toByte))))

  /** Reads a hex-prefix encoding back into a path and its leaf flag.
    *
    * Two forms are refused rather than accepted leniently, because each is a
    * second spelling of a path that already has one: a flag nibble with either
    * high bit set, and a non-zero low nibble on an even-length path. Both are
    * unreachable from [[Nibbles.toCompact]], so accepting them would let this
    * trie hold a node no conforming implementation would have written.
    *
    * Refusing the second of those is a departure from every client surveyed,
    * which all ignore that nibble. It is recorded as a departure rather than
    * left to be rediscovered, and it is safe in the direction that matters: a
    * non-canonical flag byte is a distinct byte string with a distinct digest,
    * so no trie a conforming writer built can contain one and refusing it
    * cannot change a root.
    */
  def fromCompact(compact: IArray[Byte]): Either[TrieError, (Nibbles, Boolean)] =
    if compact.isEmpty then Left(TrieError.EmptyCompactPath)
    else
      val flag = (compact(0) & 0xff) >>> 4
      val leading = compact(0) & 0x0f
      val odd = (flag & 1) != 0
      if flag > 3 then Left(TrieError.UnknownHexPrefixFlag(flag))
      else if !odd && leading != 0 then Left(TrieError.NonZeroPaddingNibble(leading))
      else
        val count = (compact.length - 1) * 2 + (if odd then 1 else 0)
        val out = new Array[Byte](count)
        var target = 0
        if odd then
          out(0) = leading.toByte
          target = 1
        var source = 1
        while source < compact.length do
          out(target) = (((compact(source) & 0xff) >>> 4)).toByte
          out(target + 1) = (compact(source) & 0x0f).toByte
          target += 2
          source += 1
        Right((new Nibbles(IArray.unsafeFromArray(out)), (flag & 2) != 0))
