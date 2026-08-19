package org.fukuii.evm

import org.fukuii.bytes.Bytes

/** The 256-bit value the machine computes over.
  *
  * ==Why this is not [[org.fukuii.bytes.UInt256]]==
  *
  * L0's `UInt256` is a VALIDATING type: `fromBigInt` refuses anything outside
  * `[0, 2^256)`, which is what makes it safe to carry an account balance or an
  * RLP-decoded quantity. The machine's word is a WRAPPING one — every operation
  * below is modulo `2^256` and silently discards the carry, which is precisely
  * the class of value L0's constructor exists to reject. Giving the L0 type
  * these operations would hand it methods contradicting its own constructor's
  * guarantee, in a type that balances and codecs already depend on for exactly
  * that guarantee. So the two stay separate and convert at the boundary.
  *
  * ==The representation is deliberately not the field's, and is swappable==
  *
  * Every production implementation surveyed represents this as four 64-bit
  * limbs: go-ethereum and erigon through `holiman/uint256`, nethermind through
  * `Nethermind.Int256`, reth through `alloy-primitives`, ethrex through
  * `ethereum-types`, and besu — the JVM peer — through its own in-tree
  * `record UInt256(long u3, long u2, long u1, long u0)` at `c2addd9424`,
  * described there as "an optimised version of BigInteger for fixed width
  * 256-bits integers". besu is visibly mid-migration: its BigInteger-backed
  * Tuweni word still serves the API surface while the four-long record serves
  * the `*Optimized` and `v2/operation/` arithmetic — the same operation set
  * built here.
  *
  * This starts on `BigInt` regardless, and the reason is that the field's
  * reason is throughput, which nothing here can currently weigh: no
  * benchmarking library is declared, so a limb implementation would be written
  * on the assumption of a benefit rather than a measurement of one.
  *
  * What makes deferring safe is that this type is opaque. No operation above
  * can observe the representation, so moving to limbs later changes this file
  * and no call site. The contract that has to be right today is the wrapping;
  * the representation is the part that has to stay changeable.
  *
  * Reversing trigger: the first measurement showing word arithmetic is a
  * bottleneck. That needs a benchmarking library, which is a dependency
  * decision and operator-gated.
  */
opaque type Word = BigInt

object Word:

  /** Width in bytes. The machine's word is fixed at this and never varies. */
  val Width: Int = 32

  private val Bits: Int = Width * 8

  /** `2^256`. Every result is reduced modulo this. */
  private val Modulus: BigInt = BigInt(1) << Bits

  /** `2^255` — the value whose bit marks a negative number in two's complement,
    * and simultaneously the most negative signed value.
    */
  private val SignBit: BigInt = BigInt(1) << (Bits - 1)

  val Zero: Word = BigInt(0)

  val One: Word = BigInt(1)

  val MaxValue: Word = Modulus - 1

  /** Reduces into `[0, 2^256)`. `BigInt.mod` is used rather than `%` because it
    * is the one that answers non-negatively for a negative left operand, which
    * is what two's-complement wrapping requires.
    */
  private def wrap(value: BigInt): Word = value.mod(Modulus)

  def apply(value: BigInt): Word = wrap(value)

  def fromLong(value: Long): Word = wrap(BigInt(value))

  /** Reads big-endian, and fewer than [[Width]] bytes are read as the low-order
    * end of the word -- which is how a `PUSH` of n bytes takes its value.
    *
    * Reads them as a magnitude, folding rather than handing them to
    * `BigInt(Array[Byte])`, which reads two's complement and would turn any
    * value with the high bit set negative. This is L0's idiom for the same
    * reason, and the fold also avoids `IArray.toArray`, which is deprecated as
    * incorrect.
    */
  def fromBytes(bytes: Bytes): Word =
    wrap(bytes.toIArray.foldLeft(BigInt(0))((acc, b) => (acc << 8) | BigInt(b & 0xff)))

  extension (self: Word)

    def toBigInt: BigInt = self

    /** Exactly [[Width]] bytes, big-endian, left-padded with zeros. */
    def toBytes: Bytes =
      val raw = self.toByteArray
      val out = new Array[Byte](Width)
      // BigInt#toByteArray is signed and minimal, so it can be shorter than the
      // word or carry one leading zero byte the word does not want.
      val src = if raw.length > Width then raw.length - Width else 0
      val len = math.min(raw.length, Width)
      System.arraycopy(raw, src, out, Width - len, len)
      Bytes.fromIArray(IArray.unsafeFromArray(out))

    def isZero: Boolean = self == Word.Zero

    /** This word read as two's complement, so `2^255` reads as the most negative
      * value rather than the largest positive one.
      */
    private def signed: BigInt = if self >= SignBit then self - Modulus else self

    // ── Arithmetic ─────────────────────────────────────────────────────────

    def add(that: Word): Word = wrap(self + that)

    def sub(that: Word): Word = wrap(self - that)

    def mul(that: Word): Word = wrap(self * that)

    /** Division by zero answers zero rather than failing: the machine defines it
      * as a value, not as an exceptional halt, so a caller has nothing to catch.
      */
    def div(that: Word): Word = if that.isZero then Word.Zero else wrap(self / that)

    /** Signed division, truncating toward zero. The one case worth naming is
      * `-2^255 / -1`, whose true result is outside the signed range; wrapping
      * returns `-2^255`, which is what the machine specifies.
      */
    def sdiv(that: Word): Word =
      if that.isZero then Word.Zero else wrap(self.signed / that.signed)

    def mod(that: Word): Word = if that.isZero then Word.Zero else wrap(self % that)

    /** Signed remainder, taking the sign of the dividend. */
    def smod(that: Word): Word =
      if that.isZero then Word.Zero else wrap(self.signed % that.signed)

    /** `(self + that) mod modulus`, where the sum is formed at arbitrary
      * precision first — so a sum that would wrap does not, before the modulus
      * is applied.
      */
    def addMod(that: Word, modulus: Word): Word =
      if modulus.isZero then Word.Zero else wrap((self + that) % modulus)

    def mulMod(that: Word, modulus: Word): Word =
      if modulus.isZero then Word.Zero else wrap((self * that) % modulus)

    def exp(exponent: Word): Word = wrap(self.modPow(exponent, Modulus))

    /** Sign-extends a two's-complement value occupying `byteIndex + 1` low-order
      * bytes out to the full width. An index at or beyond the top byte leaves
      * the word alone, there being nothing above it to extend into.
      */
    def signExtend(byteIndex: Word): Word =
      if byteIndex >= BigInt(Width - 1) then self
      else
        val bit = byteIndex.toInt * 8 + 7
        val mask = (BigInt(1) << (bit + 1)) - 1
        if self.testBit(bit) then wrap(self | ~mask) else wrap(self & mask)

    // ── Comparison ─────────────────────────────────────────────────────────

    def lessThan(that: Word): Boolean = self < that

    def greaterThan(that: Word): Boolean = self > that

    def signedLessThan(that: Word): Boolean = self.signed < that.signed

    def signedGreaterThan(that: Word): Boolean = self.signed > that.signed

    // ── Bitwise ────────────────────────────────────────────────────────────

    def and(that: Word): Word = wrap(self & that)

    def or(that: Word): Word = wrap(self | that)

    def xor(that: Word): Word = wrap(self ^ that)

    def not: Word = wrap(~self)

    /** The byte at `index` counted from the most significant, as a word. An
      * index at or beyond the width answers zero, which the machine defines as
      * a value rather than a fault.
      */
    def byte(index: Word): Word =
      if index >= BigInt(Width) then Word.Zero
      else wrap((self >> ((Width - 1 - index.toInt) * 8)) & BigInt(0xff))
