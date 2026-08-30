package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The machine's word, checked at the edges the executable specification calls
  * out rather than at the arithmetic that any integer type would get right.
  *
  * Expected values are derived from `forks/frontier/vm/instructions/arithmetic.py`
  * at `ccaaaba58`, read rather than recalled — including the two cases that file
  * singles out with a branch of their own: signed division of the most negative
  * value by minus one, and a sign-extension index past the top byte.
  */
class WordSpec extends AnyFlatSpec:

  private val TwoTo255: BigInt = BigInt(1) << 255
  private def w(value: BigInt): Word = Word(value)

  "add" should "wrap at the modulus rather than growing" in
    assert(Word.MaxValue.add(Word.One) == Word.Zero, "the carry out of the top bit is discarded, it is not an error")

  "sub" should "wrap below zero into the top of the range" in
    assert(Word.Zero.sub(Word.One) == Word.MaxValue, "zero minus one is all ones in two's complement")

  "mul" should "keep only the low 256 bits of the product" in {
    val half = w(BigInt(1) << 128)
    assert(half.mul(half) == Word.Zero, "2^128 squared is 2^256, whose low 256 bits are zero")
  }

  "div" should "answer zero when the divisor is zero" in
    assert(w(7).div(Word.Zero) == Word.Zero, "the machine defines division by zero as a value, so nothing is thrown")

  "sdiv" should "answer zero when the divisor is zero" in
    assert(w(7).sdiv(Word.Zero) == Word.Zero, "signed division by zero is a value for the same reason")

  it should "answer the most negative value when it divides that by minus one" in {
    val mostNegative = w(TwoTo255)
    assert(
      mostNegative.sdiv(Word.Zero.sub(Word.One)) == mostNegative,
      "the true quotient 2^255 is outside the signed range, and the specification gives this case its own branch"
    )
  }

  it should "truncate toward zero rather than flooring" in
    assert(w(BigInt(-7)).sdiv(w(2)) == w(BigInt(-3)), "-7 / 2 is -3 truncated, not -4 floored")

  "mod" should "answer zero when the modulus is zero" in
    assert(w(7).mod(Word.Zero) == Word.Zero, "as with division, the machine defines it rather than failing")

  "smod" should "take the sign of the dividend" in
    assert(w(BigInt(-7)).smod(w(3)) == w(BigInt(-1)), "the remainder follows the dividend, so -7 smod 3 is -1")

  "addMod" should "form the sum before reducing, not after" in
    assert(
      Word.MaxValue.addMod(Word.One, w(8)) == Word.Zero,
      "the sum is 2^256 at full precision and reduces to 0 mod 8; wrapping first would have given 0 mod 8 from 0 by luck rather than by rule"
    )

  it should "answer zero when the modulus is zero" in
    assert(w(3).addMod(w(4), Word.Zero) == Word.Zero, "a zero modulus is defined as a zero result")

  "mulMod" should "form the product before reducing, not after" in {
    val half = w(BigInt(1) << 128)
    assert(half.mulMod(half, w(7)) == w((BigInt(1) << 256) % 7), "the product is taken at full precision first")
  }

  "exp" should "be modular rather than overflowing" in
    assert(w(2).exp(w(256)) == Word.Zero, "2^256 reduces to zero in the word")

  "signExtend" should "fill the high bytes when the sign bit of the named byte is set" in
    assert(
      w(0xff).signExtend(Word.Zero) == Word.MaxValue,
      "0xff read as one signed byte is -1, which is all ones extended"
    )

  it should "discard the high bytes when that sign bit is clear" in
    assert(w(0xff7f).signExtend(Word.Zero) == w(0x7f), "byte 0 is 0x7f, non-negative, so everything above it goes")

  it should "leave the word alone for an index at or beyond the top byte" in
    assert(w(0xff).signExtend(w(31)) == w(0xff), "there is nothing above the top byte to extend into")

  "byte" should "read from the most significant end" in
    assert(Word.MaxValue.byte(Word.Zero) == w(0xff), "index zero is the most significant byte, not the least")

  it should "answer zero for an index at or beyond the width" in
    assert(Word.MaxValue.byte(w(32)) == Word.Zero, "an index past the word is a value of zero, not a fault")

  "signedLessThan" should "order a negative below a positive" in
    assert(Word.MaxValue.signedLessThan(Word.One), "all-ones is -1 signed, which is below 1")

  "lessThan" should "order that same pair the other way unsigned" in
    assert(Word.One.lessThan(Word.MaxValue), "unsigned, all-ones is the largest value there is")

  "not" should "invert every bit" in
    assert(Word.Zero.not == Word.MaxValue, "the complement of zero is all ones")

  "toBytes" should "always be the full width, left-padded" in
    assert(Word.One.toBytes.toIArray.length == Word.Width, "a word is a fixed-width value however small its magnitude")

  "fromBytes" should "read big-endian" in
    assert(Word.fromBytes(Bytes.fromIArray(IArray[Byte](1, 0))) == w(256), "the first byte is the most significant")

  // ── The shifts EIP-145 introduced ─────────────────────────────────────────
  //
  // The in-range cases are the ordinary ones and are asserted once each. The
  // OUT-OF-RANGE cases carry the weight: all three saturate, two of them to
  // zero and one of them to a value that depends on the sign of its operand, so
  // an implementation that treated the three alike would be right twice and
  // silently wrong on the third.

  "shiftLeft" should "move bits toward the most significant end" in
    assert(Word.One.shiftLeft(w(1)) == w(2), "shifting one left by one is two")

  it should "discard bits carried past the top rather than widening" in
    assert(
      Word.MaxValue.shiftLeft(w(1)) == Word.MaxValue.sub(Word.One),
      "all-ones shifted left loses the top bit and gains a zero at the bottom"
    )

  it should "answer zero for a shift at the width" in
    assert(Word.MaxValue.shiftLeft(w(256)) == Word.Zero, "a shift of the full width is a defined zero, not a fault")

  it should "answer zero for a shift far beyond any representable width" in
    assert(Word.One.shiftLeft(Word.MaxValue) == Word.Zero, "a shift of 2^256-1 must answer zero rather than narrow")

  it should "answer zero for an out-of-range shift whose LOW BITS are in range" in
    // THE ONLY SHL CASE THAT DISCRIMINATES, and it was found by mutation after
    // two that do not. `shift.toInt` truncates to the low 32 bits, so 2^32
    // narrows to 0 and an unguarded shift would answer the operand unchanged
    // where the correct answer is zero.
    //
    // The two neighbours above are EQUIVALENT MUTANTS and are kept as
    // documentation rather than as catchers: at exactly the width, `wrap`
    // already reduces `MaxValue << 256` to zero, and at 2^256-1 the truncation
    // lands on -1, which BigInt shifts the other way to the same zero. Removing
    // the guard entirely leaves both passing. Do not read either as evidence
    // the bound is enforced.
    assert(
      Word.One.shiftLeft(w(BigInt(1) << 32)) == Word.Zero,
      "a shift of 2^32 narrows to 0 and would answer the operand unchanged"
    )

  "shiftRight" should "move bits toward the least significant end" in
    assert(w(2).shiftRight(w(1)) == Word.One, "shifting two right by one is one")

  it should "fill from the top with zero rather than with the sign" in
    // This is the whole difference from the arithmetic shift, asserted on the
    // one operand where the two disagree most loudly.
    assert(
      Word.MaxValue.shiftRight(w(255)) == Word.One,
      "all-ones is -1 signed, and a LOGICAL shift must not carry that sign down"
    )

  it should "answer zero for a shift at the width" in
    assert(Word.MaxValue.shiftRight(w(256)) == Word.Zero, "a shift of the full width is a defined zero, not a fault")

  it should "answer zero for an out-of-range shift whose LOW BITS are in range" in
    // The SHL note above applies here for the same reason: this is the case
    // that catches a missing guard, and the at-the-width one does not.
    assert(
      Word.MaxValue.shiftRight(w(BigInt(1) << 32)) == Word.Zero,
      "a shift of 2^32 narrows to 0 and would answer the operand unchanged"
    )

  "shiftRightArithmetic" should "fill from the top with the sign bit" in
    assert(
      Word.MaxValue.shiftRightArithmetic(w(255)) == Word.MaxValue,
      "all-ones is -1 signed, and -1 shifted arithmetically stays -1"
    )

  it should "behave as a logical shift on a non-negative value" in
    assert(w(2).shiftRightArithmetic(w(1)) == Word.One, "a positive operand has no sign bits to carry down")

  it should "saturate a NEGATIVE operand to all ones, not to zero" in
    // The case that separates this from the other two shifts. A single zero
    // answer for every out-of-range shift is right for a positive operand and
    // wrong here.
    assert(
      Word.MaxValue.shiftRightArithmetic(w(256)) == Word.MaxValue,
      "-1 shifted right by any amount is -1, so saturating to zero is wrong"
    )

  it should "saturate a NON-NEGATIVE operand to zero" in
    assert(
      Word.One.shiftRightArithmetic(w(256)) == Word.Zero,
      "a positive operand saturates to zero, which is the other half of the branch"
    )

  it should "saturate on a shift far beyond any representable width" in
    assert(
      Word.MaxValue.shiftRightArithmetic(Word.MaxValue) == Word.MaxValue,
      "the guard must precede the narrowing here too"
    )
