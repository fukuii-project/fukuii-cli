package org.fukuii.bytes

/** A 256-bit unsigned integer — the width of every quantity a block carries.
  *
  * ==Why an opaque type here and a class for [[Hash]] and [[Address]]==
  *
  * The two shapes are decided separately, and the reason those are classes does
  * not reach this one. An opaque type erases, so it inherits its underlying
  * type's equality; an array has reference equality, which is why a byte-shaped
  * value wrapping one has to be a class that supplies `equals` itself.
  * `BigInt` already has the structural equality a value type needs, so the
  * cheaper shape is also the correct one.
  *
  * ==What this offers==
  *
  * Construction, ordering, and the two big-endian byte forms a block's
  * quantities are carried in. Modular arithmetic over 2^256 belongs to the
  * layer that executes rather than the one that carries values, and is not
  * here.
  */
opaque type UInt256 = BigInt

object UInt256:

  /** The width of the fixed-length big-endian form, in bytes. */
  val Width: Int = 32

  private val Modulus: BigInt = BigInt(1) << (Width * 8)

  val Zero: UInt256 = BigInt(0)

  /** 2^256 - 1. */
  val MaxValue: UInt256 = Modulus - 1

  /** Rejects a negative value and any value at or above 2^256.
    *
    * The upper bound is the interesting one: 2^256 is a perfectly ordinary RLP
    * scalar — the conformance corpus publishes it as a valid encoding — and it
    * is the first value that is not a word.
    */
  def fromBigInt(value: BigInt): Either[BytesError, UInt256] =
    if value.signum < 0 || value >= Modulus then Left(BytesError.OutOfRange)
    else Right(value)

  def fromLong(value: Long): Either[BytesError, UInt256] = fromBigInt(BigInt(value))

  /** Reads a big-endian byte sequence of at most [[Width]] bytes.
    *
    * Any length up to the width is accepted, including empty, because that is
    * what RLP delivers: the scalar rule encodes a quantity in its minimal form,
    * so a decoder sees a short sequence for a small value and no bytes at all
    * for zero. Rejecting a short sequence here would reject the canonical
    * encoding of every small number.
    *
    * This reads the bytes as a magnitude, so it cannot produce a negative value
    * and needs no sign check — unlike `BigInt(Array[Byte])`, which reads two's
    * complement and would turn any value with the high bit set into a negative.
    */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, UInt256] =
    if bytes.length > Width then Left(BytesError.BadWidth(Width, bytes.length))
    else Right(bytes.foldLeft(BigInt(0))((acc, b) => (acc << 8) | BigInt(b & 0xff)))

  def fromHex(s: String): Either[BytesError, UInt256] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)

  extension (value: UInt256)

    def toBigInt: BigInt = value

    /** Exactly [[Width]] bytes, left-padded with zero.
      *
      * The form a word takes when it is a storage slot or part of a hash
      * preimage, where the width is fixed and the padding is significant.
      */
    def toBytes: IArray[Byte] = FixedWidth.align(minimal(value), Width)

    /** The minimal big-endian form: no leading zero byte, and empty for zero.
      *
      * This is the Yellow Paper's scalar rule and it is what RLP encodes a
      * quantity as. A leading zero byte is not an alternative spelling of the
      * same number — it is a non-canonical encoding that a decoder must reject.
      */
    def toMinimalBytes: IArray[Byte] = minimal(value)

  /** The delegate is named rather than left to implicit search, and that is
    * load-bearing rather than stylistic.
    *
    * Inside this object the opaque type is EQUAL to `BigInt`, so a search for
    * `Ordering[BigInt]` can resolve back to this very instance. That is a
    * recursive lazy val, which the language reference calls undefined behavior
    * and which may deadlock on first use — and being lazy, it need not fail on
    * the first run or anywhere near the code that caused it. Naming the
    * delegate means no search runs.
    */
  given Ordering[UInt256] =
    Ordering.by[UInt256, BigInt](_.toBigInt)(using scala.math.Ordering.BigInt)

  /** `BigInt.toByteArray` is two's complement, so a positive value whose top
    * bit is set carries a leading zero byte. That byte is sign, not magnitude,
    * and leaving it in would produce a non-canonical scalar.
    */
  private def minimal(value: BigInt): IArray[Byte] =
    if value.signum == 0 then IArray.empty[Byte]
    else
      val raw = value.toByteArray
      val from = if raw(0) == 0 then 1 else 0
      IArray.unsafeFromArray(java.util.Arrays.copyOfRange(raw, from, raw.length))
