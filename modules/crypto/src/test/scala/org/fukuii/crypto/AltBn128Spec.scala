package org.fukuii.crypto

import org.scalatest.flatspec.AnyFlatSpec

/** The facts `AltBn128` derives everything else from, asserted rather than
  * trusted.
  *
  * ==What this file is for, and what it deliberately is not==
  *
  * The curve's ANSWERS are certified against three published corpora in
  * `org.fukuii.evm.AltBn128PrecompilePropSpec`, which is where the vectors are.
  * What is here is the algebra underneath them: the two identities that pin the
  * one parameter neither proposal states, the factorization the pairing's final
  * exponent is taken apart along, and a bilinearity that holds for every
  * correct pairing and for essentially no incorrect one.
  *
  * ==Why bilinearity earns its place beside a corpus of six hundred vectors==
  *
  * A corpus states what a pairing answers for the inputs somebody wrote down.
  * Bilinearity states a relation between answers, so it is refuted by an error
  * the corpus's authors did not happen to reach -- and it is built here out of
  * this build's own scalar multiplication rather than out of anything published,
  * which makes it independent of every corpus rather than a rewording of one.
  *
  * The two generators are `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-197.md`
  * § Definition of the groups, taken in the decimal the document writes them
  * in.
  */
class AltBn128Spec extends AnyFlatSpec:

  private def word(value: BigInt): IArray[Byte] =
    val out = new Array[Byte](32)
    val raw = value.toByteArray
    val taken = if raw.length < 32 then raw.length else 32
    var index = 0
    while index < taken do
      out(32 - taken + index) = raw(raw.length - taken + index)
      index += 1
    IArray.unsafeFromArray(out)

  private def joined(words: BigInt*): IArray[Byte] = words.foldLeft(IArray.empty[Byte])(_ ++ word(_))

  /** `P1 = (1, 2)`, the first group's generator. */
  private val firstGenerator: IArray[Byte] = joined(BigInt(1), BigInt(2))

  /** `P2`, the second group's generator, each coefficient pair carrying the
    * multiple of `i` first as the document's encoding requires.
    */
  private val secondGenerator: IArray[Byte] = joined(
    BigInt("11559732032986387107991004021392285783925812861821192530917403151452391805634"),
    BigInt("10857046999023057135944570762232829481370756359578518086990519993285655852781"),
    BigInt("4082367875863433681332203403145435568316851327593401208105741076214120093531"),
    BigInt("8495653923123431417604973247489272438418190587263600148770280649306958101930")
  )

  private def scaledGenerator(scalar: BigInt): IArray[Byte] =
    AltBn128.scaled(firstGenerator ++ word(scalar)).getOrElse(IArray.empty[Byte])

  private def pairedWithSecond(points: IArray[Byte]*): Option[Boolean] =
    AltBn128.pairingIsOne(points.foldLeft(IArray.empty[Byte])(_ ++ _ ++ secondGenerator))

  "the field modulus" should "be the quartic in the curve parameter that EIP-196 and EIP-197 state" in {
    val u = AltBn128.Parameter
    assert(
      AltBn128.FieldModulus == 36 * u.pow(4) + 36 * u.pow(3) + 24 * u.pow(2) + 6 * u + 1,
      "the modulus the two documents state is not this parameter's"
    )
  }

  it should "be three modulo four, which is what makes conjugation the Frobenius endomorphism" in
    assert(AltBn128.FieldModulus.mod(4) == 3, "a different residue would make the extension's Frobenius something else")

  it should "be one modulo six, which is what makes the twist's Frobenius constants exist" in
    assert(AltBn128.FieldModulus.mod(6) == 1, "a sixth root of the non-residue needs the exponent to be a whole number")

  "the group order" should "be the other quartic in the same parameter" in {
    val u = AltBn128.Parameter
    assert(
      AltBn128.GroupOrder == 36 * u.pow(4) + 36 * u.pow(3) + 18 * u.pow(2) + 6 * u + 1,
      "the order EIP-197 states is not this parameter's"
    )
  }

  it should "divide the twelfth cyclotomic polynomial at the field modulus" in {
    val p = AltBn128.FieldModulus
    assert(
      (p.pow(4) - p.pow(2) + 1).mod(AltBn128.GroupOrder) == 0,
      "the pairing's final exponent is not a whole number unless it does"
    )
  }

  "the pairing's final exponent" should "factor into the two cheap steps and the one it pays for" in {
    val p = AltBn128.FieldModulus
    assert(
      (p.pow(12) - 1) / AltBn128.GroupOrder == (p.pow(6) - 1) * (p.pow(2) + 1) * AltBn128.HardExponent,
      "raising to the sixth and second Frobenius powers does not compose to the whole exponent"
    )
  }

  "the pairing" should "be non-degenerate on the two generators the document states" in
    assert(
      pairedWithSecond(firstGenerator) == Some(false),
      "one generator against the other pairs to one, so the pairing is trivial"
    )

  it should "be linear in its first argument" in {
    val left = BigInt("7331000000000000000000000000000000000000000000000000000000000091")
    val right = BigInt("4242000000000000000000000000000000000000000000000000000000000007")
    val closing = AltBn128.GroupOrder - (left + right).mod(AltBn128.GroupOrder)
    assert(
      pairedWithSecond(scaledGenerator(left), scaledGenerator(right), scaledGenerator(closing)) == Some(true),
      "three points summing to nothing must pair to one"
    )
  }

  it should "refuse the closing point that is one step out" in {
    val left = BigInt("7331000000000000000000000000000000000000000000000000000000000091")
    val right = BigInt("4242000000000000000000000000000000000000000000000000000000000007")
    val closing = AltBn128.GroupOrder - (left + right + 1).mod(AltBn128.GroupOrder)
    assert(
      pairedWithSecond(scaledGenerator(left), scaledGenerator(right), scaledGenerator(closing)) == Some(false),
      "three points summing to one point must not pair to one, or the check above proves nothing"
    )
  }
