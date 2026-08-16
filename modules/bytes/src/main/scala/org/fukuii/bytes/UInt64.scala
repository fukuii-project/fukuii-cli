package org.fukuii.bytes

/** A 64-bit unsigned integer — the protocol's machine word.
  *
  * A block number, a gas figure, a timestamp, a nonce and a withdrawal's
  * counters are all `uint64`, and the platform has no unsigned primitive. This
  * is an opaque type over `Long` holding the raw 64 bits, read as unsigned.
  *
  * ==Why the full range has to be representable==
  *
  * Not every `uint64` a block can carry is a value the protocol accepts — a gas
  * limit at or above 2^63 is invalid, and the conformance corpus asserts it. But
  * **the block still has to be decoded before it can be judged**, and a type
  * that cannot hold the value conflates "this client cannot read it" with "the
  * protocol forbids it". Those are different answers and only the second is
  * consensus. Decoding belongs here; deciding validity belongs to the layer that
  * owns fork rules.
  *
  * ==The printed form is not the value, above 2^63==
  *
  * `toString` is the underlying `Long`'s, so a value with the high bit set
  * prints as a NEGATIVE number. **Use [[show]] for anything a human or a log
  * reads.**
  *
  * Two separate mechanisms put it beyond reach, and only the first is erasure.
  * Erasure is why the `Long`'s `toString` is the one that *runs*. What stops it
  * being replaced is that every value already has that member from `Any`, so an
  * extension named `toString` is not merely passed over — the compiler refuses
  * it outright, `[E194] already has a member with the same name`.
  *
  * The compiler's interpolation warning does not cover this either. It is
  * enabled, and it fires on reference types; `build.sbt` records that it does
  * not fire for primitives by design, which is what this type erases to.
  */
opaque type UInt64 = Long

object UInt64:

  /** The width of the fixed-length big-endian form, in bytes. */
  val Width: Int = 8

  val Zero: UInt64 = 0L

  /** 2^64 - 1 — every bit set, which as a signed `Long` is -1. */
  val MaxValue: UInt64 = -1L

  private val Modulus: BigInt = BigInt(1) << (Width * 8)

  /** Takes a NON-NEGATIVE signed value.
    *
    * A negative `Long` is rejected rather than reinterpreted: a caller holding
    * one means a number, not a bit pattern, and silently reading it as a value
    * near 2^64 would turn an arithmetic slip into a plausible quantity. Use
    * [[fromBits]] where the bits really are the input.
    */
  def fromLong(value: Long): Either[BytesError, UInt64] =
    if value < 0 then Left(BytesError.OutOfRange) else Right(value)

  /** Takes the raw 64 bits, including patterns above 2^63. */
  def fromBits(value: Long): UInt64 = value

  def fromBigInt(value: BigInt): Either[BytesError, UInt64] =
    if value.signum < 0 || value >= Modulus then Left(BytesError.OutOfRange)
    else Right(value.toLong)

  /** Reads a big-endian byte sequence of at most [[Width]] bytes.
    *
    * Every 8-byte sequence is a valid value, so unlike the signed reading of the
    * same bytes there is no in-range failure to report — only a too-wide input.
    */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, UInt64] =
    if bytes.length > Width then Left(BytesError.BadWidth(Width, bytes.length))
    else Right(bytes.foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL)))

  def fromHex(s: String): Either[BytesError, UInt64] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)

  extension (value: UInt64)

    /** The unsigned value. Widening to `BigInt` is what makes it printable and
      * comparable against a quantity of another width without reinterpretation.
      */
    def toBigInt: BigInt =
      if value >= 0 then BigInt(value) else BigInt(value) + Modulus

    /** The raw bits, for a caller that needs the machine word itself. */
    def toBits: Long = value

    /** The unsigned decimal form. `toString` is the signed one and lies above
      * 2^63; this does not.
      */
    def show: String = java.lang.Long.toUnsignedString(value)

    /** Exactly [[Width]] bytes, left-padded with zero. */
    def toBytes: IArray[Byte] =
      IArray.from((0 until Width).map(i => ((value >>> ((Width - 1 - i) * 8)) & 0xffL).toByte))

    /** The minimal big-endian form: no leading zero byte, empty for zero.
      *
      * The Yellow Paper's scalar rule, and what RLP encodes a quantity as.
      */
    def toMinimalBytes: IArray[Byte] =
      if value == 0 then IArray.empty[Byte]
      else
        var width = Width
        while width > 1 && ((value >>> ((width - 1) * 8)) & 0xffL) == 0 do width -= 1
        IArray.from((0 until width).map(i => ((value >>> ((width - 1 - i) * 8)) & 0xffL).toByte))

  /** The comparison is UNSIGNED, and writing it out is a correctness
    * requirement rather than the stylistic one it looks like.
    *
    * Delegating to the underlying type's `Ordering` would compare these values
    * as signed, putting everything at or above 2^63 below zero — so the
    * ordinary hazard of implicit search resolving back to this instance is not
    * even the worst outcome available. Naming the comparator avoids the search
    * and gets the semantics right in one move.
    */
  given Ordering[UInt64] = (a, b) => java.lang.Long.compareUnsigned(a, b)
