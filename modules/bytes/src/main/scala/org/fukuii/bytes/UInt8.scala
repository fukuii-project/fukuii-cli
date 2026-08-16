package org.fukuii.bytes

/** An 8-bit unsigned integer.
  *
  * ==Why this exists, when the platform has a byte already==
  *
  * `Byte` is signed, so the upper half of the range reads as negative and the
  * type cannot hold 128 through 255 as the numbers they are. This is an opaque
  * type over `Int` holding a value the constructors keep inside 0 to 255.
  *
  * ==It exists because a specification bounds a field at 2^8, not for symmetry==
  *
  * EIP-7702 states the bound directly — `assert auth.y_parity < 2**8` — and the
  * executable specification types that field `U8`, so an over-wide value is
  * refused when the bytes are read rather than when the transaction is judged.
  * The conformance corpus agrees: a `y_parity` of 256 appears in the published
  * rejection set.
  *
  * That is what forces a narrow type rather than a bound checked in a codec. A
  * field wide enough to hold 256 would make a value that encodes to bytes its
  * own decoder refuses — and a codec whose encode and decode disagree about
  * what is admissible has stopped round-tripping, which is the one property
  * every instance in this project is required to have.
  *
  * @see
  *   [[UInt64]], which is the protocol's machine word and the type most
  *   quantities take. This one is for a field a specification has explicitly
  *   bounded at a single byte, and it has one such consumer today.
  */
opaque type UInt8 = Int

object UInt8:

  /** The width of the fixed-length form, in bytes. */
  val Width: Int = 1

  val Zero: UInt8 = 0

  /** 2^8 - 1. */
  val MaxValue: UInt8 = 0xff

  private val Modulus: Int = 1 << (Width * 8)

  def fromInt(value: Int): Either[BytesError, UInt8] =
    if value < 0 || value >= Modulus then Left(BytesError.OutOfRange) else Right(value)

  def fromBigInt(value: BigInt): Either[BytesError, UInt8] =
    if value.signum < 0 || value >= Modulus then Left(BytesError.OutOfRange)
    else Right(value.toInt)

  /** Reads a big-endian byte sequence of at most [[Width]] bytes.
    *
    * The empty sequence is zero, which is the scalar rule's own spelling of it
    * rather than a special case admitted here.
    */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, UInt8] =
    if bytes.length > Width then Left(BytesError.BadWidth(Width, bytes.length))
    else Right(bytes.foldLeft(0)((acc, b) => (acc << 8) | (b & 0xff)))

  def fromHex(s: String): Either[BytesError, UInt8] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)

  extension (value: UInt8)

    def toInt: Int = value

    def toBigInt: BigInt = BigInt(value)

    /** Exactly [[Width]] bytes. */
    def toBytes: IArray[Byte] = IArray(value.toByte)

    /** The minimal big-endian form: empty for zero, one byte otherwise.
      *
      * The Yellow Paper's scalar rule, and what RLP encodes a quantity as.
      */
    def toMinimalBytes: IArray[Byte] =
      if value == 0 then IArray.empty[Byte] else IArray(value.toByte)

  /** Unsigned, and named rather than searched for.
    *
    * The underlying type's own comparison happens to agree here, since the
    * whole range is non-negative as an `Int` — but supplying the comparator
    * explicitly is what keeps implicit search from resolving back to this
    * instance inside the scope where the two types are equal.
    */
  given Ordering[UInt8] = (a, b) => Integer.compare(a, b)
