package org.fukuii.bytes

/** A reason a byte sequence could not become a fixed-width value. */
enum BytesError:
  case BadHex(cause: HexError)
  case BadWidth(expected: Int, actual: Int)

private[bytes] object FixedWidth:

  /** Right-aligns `bytes` into exactly `width` bytes: keeps the rightmost
    * `width` when there are too many, and left-pads with zero when there are
    * too few.
    *
    * The alignment is the whole of the semantics and it is easy to get
    * backwards. A value that is left-aligned instead is a different number, and
    * for a value derived from a hash it is a different account.
    */
  def align(bytes: IArray[Byte], width: Int): IArray[Byte] =
    val out    = new Array[Byte](width)
    val src    = if bytes.length > width then bytes.length - width else 0
    val copied = bytes.length - src
    var i      = 0
    while i < copied do
      out(width - copied + i) = bytes(src + i)
      i += 1
    IArray.unsafeFromArray(out)

  /** `java.util.Arrays.hashCode` semantics, written out because reaching the
    * backing array would need a cast.
    */
  def hash(bytes: IArray[Byte]): Int =
    var h = 1
    var i = 0
    while i < bytes.length do
      h = 31 * h + bytes(i)
      i += 1
    h

  def sameBytes(a: IArray[Byte], b: IArray[Byte]): Boolean =
    if a.length != b.length then false
    else
      var i    = 0
      var same = true
      while i < a.length && same do
        if a(i) != b(i) then same = false
        i += 1
      same
