package org.fukuii.bytes

/** A reason a byte sequence could not become a fixed-width value. */
enum BytesError:
  case BadHex(cause: HexError)
  case BadWidth(expected: Int, actual: Int)
  case OutOfRange

/** Visible across the project rather than to `bytes` alone, because a
  * fixed-width value type is not confined to this module: a block's logs bloom
  * is 256 bytes and its nonce is 8, and both are domain concepts that belong
  * with the types that carry them.
  *
  * The alternative was a second copy of [[FixedWidth.hash]], and that one is
  * not ordinary duplication — it is the avalanche the comment below argues for,
  * on keys an attacker supplies. Two copies of it drift, and the copy that
  * drifts is the one nobody re-derives the reasoning for.
  */
private[fukuii] object FixedWidth:

  /** Right-aligns `bytes` into exactly `width` bytes: keeps the rightmost
    * `width` when there are too many, and left-pads with zero when there are
    * too few.
    *
    * The alignment is the whole of the semantics and it is easy to get
    * backwards. A value that is left-aligned instead is a different number, and
    * for a value derived from a hash it is a different account.
    */
  def align(bytes: IArray[Byte], width: Int): IArray[Byte] =
    val out = new Array[Byte](width)
    val src = if bytes.length > width then bytes.length - width else 0
    val copied = bytes.length - src
    var i = 0
    while i < copied do
      out(width - copied + i) = bytes(src + i)
      i += 1
    IArray.unsafeFromArray(out)

  /** A hash code for values used as `Map` and `Set` keys.
    *
    * These values are keys an attacker supplies — a hash or an address decoded
    * from a peer's message — and Scala's CHAMP hash map has no tree-bin
    * fallback, so colliding keys stay in a linear list and a supply of them is
    * quadratic work rather than a slowdown. **The mixing therefore has to
    * avalanche, not merely accumulate.** A linear form such as `31 * h + b`
    * does not: under it `[0x00, 0x1f]` and `[0x01, 0x00]` have the same code,
    * and pairs like that fall out of arithmetic rather than out of search.
    *
    * FNV-1a over a 64-bit accumulator with a final avalanche, folded to 32
    * bits, so nothing is allocated and the backing array needs no cast.
    *
    * **The bound of this, stated because it is easy to over-read:** FNV-1a is
    * unseeded and is not collision-resistant against an adversary computing
    * offline. It removes the class of collisions constructible by hand, not the
    * class findable by search. Closing that needs a per-process seed, which
    * carries its own consequences and is not decided here.
    */
  def hash(bytes: IArray[Byte]): Int =
    var h = 0xcbf29ce484222325L
    var i = 0
    while i < bytes.length do
      h = (h ^ (bytes(i) & 0xffL)) * 0x100000001b3L
      i += 1
    var x = h
    x ^= x >>> 33
    x *= 0xff51afd7ed558ccdL
    x ^= x >>> 33
    (x ^ (x >>> 32)).toInt

  def sameBytes(a: IArray[Byte], b: IArray[Byte]): Boolean =
    if a.length != b.length then false
    else
      var i = 0
      var same = true
      while i < a.length && same do
        if a(i) != b(i) then same = false
        i += 1
      same
