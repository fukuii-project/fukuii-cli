package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The contract of the three natives that answer over `alt_bn128`, stated as
  * behavior rather than as a vector.
  *
  * The sibling [[AltBn128PrecompilePropSpec]] carries the corpora. What is here
  * is what no vector in them establishes: the price at each length, the order
  * the charge and the length rule are settled in, the boundary between a field
  * element and the modulus, and that a refusal is a halt rather than an empty
  * answer.
  *
  * Expected behavior is `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-196.md` and
  * `EIPS/eip-197.md`, read against `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/byzantium/vm/precompiled_contracts/alt_bn128.py`, and against
  * `ethereum/go-ethereum-pow` @ `v1.10.26`, `core/vm/contracts.go`.
  */
class AltBn128PrecompileSpec extends AnyFlatSpec:

  private val add = Precompile.AltBn128Add(BigInt(500))

  private val mul = Precompile.AltBn128Mul(BigInt(40000))

  private val pairing = Precompile.AltBn128PairingCheck(BigInt(100000), BigInt(80000))

  private val fieldModulus = org.fukuii.crypto.AltBn128.FieldModulus

  private val groupOrder = org.fukuii.crypto.AltBn128.GroupOrder

  private def word(value: BigInt): String = Word(value).toBytes.toHex

  private def bytesOf(hex: String): Bytes = EvmFixtures.bytesOf(hex)

  private val zeroWord = word(BigInt(0))

  /** `P1 = (1, 2)`, EIP-197's generator of the first group and the smallest
    * point either document names.
    */
  private val generator = word(BigInt(1)) + word(BigInt(2))

  private val infinity = zeroWord + zeroWord

  /** A point of the second group that is on the twist curve and NOT of the
    * group's order, so it is admitted by every rule EIP-196 states and refused
    * by the one EIP-197 adds.
    *
    * Its first coordinate is 8, which is what makes it worth naming rather than
    * reading out of the corpus: everything else about the encoding is zero
    * except the ordinate the curve equation forces. Taken from the fixture
    * release, `tests@v20.0.1`,
    * `blockchain_tests/for_byzantium/byzantium/eip197_ec_pairing/ecpairing_fuzzed/invalid_g2_subgroup`,
    * case `invalid_g2_subgroup_0`.
    */
  private val offSubgroup =
    zeroWord + word(BigInt(8)) +
      "00104e75a20b641566a0c71c9069a5256391aa31e22021d36c037c108dfb79c66" +
      "200bf257ae3d66a589214f980a2ae34f9544be2fcbcc13b21f4c1642f31aa4d20"

  /** The second group's generator, which is on the same curve AND of the
    * group's order -- so a rule that refused the point above by accident would
    * refuse this one too.
    */
  private val secondGenerator =
    word(BigInt("11559732032986387107991004021392285783925812861821192530917403151452391805634")) +
      word(BigInt("10857046999023057135944570762232829481370756359578518086990519993285655852781")) +
      word(BigInt("4082367875863433681332203403145435568316851327593401208105741076214120093531")) +
      word(BigInt("8495653923123431417604973247489272438418190587263600148770280649306958101930"))

  // ── what each of the two documents charges ──────────────────────────────

  "adding two points" should "cost the same however much was supplied" in
    assert(
      add.gasFor(Bytes.Empty) == BigInt(500) && add.gasFor(bytesOf(generator + generator + "ff" * 512)) == BigInt(500),
      "EIP-196 states one figure for ECADD and the input it reads is a fixed width"
    )

  "scaling a point" should "cost the same however much was supplied" in
    assert(
      mul.gasFor(Bytes.Empty) == BigInt(40000) && mul.gasFor(bytesOf(generator + zeroWord + "ff" * 512)) == BigInt(
        40000
      ),
      "EIP-196 states one figure for ECMUL and the input it reads is a fixed width"
    )

  "a pairing check" should "cost the base and one more per whole pair" in
    assert(
      pairing.gasFor(Bytes.Empty) == BigInt(100000) &&
        pairing.gasFor(bytesOf("00" * 192)) == BigInt(180000) &&
        pairing.gasFor(bytesOf("00" * 384)) == BigInt(260000) &&
        pairing.gasFor(bytesOf("00" * 1920)) == BigInt(900000),
      "EIP-197 charges 80,000 for each 192 bytes over a base of 100,000"
    )

  it should "count only the whole pairs of an input that is not a whole number of them" in
    assert(
      pairing.gasFor(bytesOf("00" * 191)) == BigInt(100000) &&
        pairing.gasFor(bytesOf("00" * 193)) == BigInt(180000),
      "the specification charges from the floor of the length over 192 and refuses afterwards"
    )

  it should "refuse an input that is not a whole number of pairs, having charged for it" in
    assert(
      pairing.run(bytesOf("00" * 191)) == Left(Halt.InvalidParameter),
      "EIP-197: if the input length is not a multiple of 192, the call fails"
    )

  // ── the padding rule, which is the encoding rather than a convenience ────

  "adding two points" should "read a short input as one padded with zeroes" in
    assert(
      add.run(bytesOf(generator)) == add.run(bytesOf(generator + infinity)),
      "a supplied point and a point at infinity are what 64 bytes name"
    )

  it should "ignore everything past the two points it reads" in
    assert(
      add.run(bytesOf(generator + generator + "ff" * 64)) == add.run(bytesOf(generator + generator)),
      "EIP-196: surplus bytes at the end are ignored"
    )

  it should "answer the point at infinity for an input of no bytes at all" in
    assert(
      add.run(Bytes.Empty).map(_.toHex) == Right(infinity),
      "EIP-196 names empty as an input both contracts succeed on"
    )

  "scaling a point" should "read a short input as one padded with zeroes" in
    assert(
      mul.run(bytesOf(generator)) == mul.run(bytesOf(generator + zeroWord)),
      "a supplied point and a scalar of zero are what 64 bytes name"
    )

  it should "answer the point at infinity for an input of no bytes at all" in
    assert(mul.run(Bytes.Empty).map(_.toHex) == Right(infinity), "nothing scaled by nothing is nothing")

  // ── the scalar, which is the one field with no upper bound ──────────────

  it should "answer the point at infinity for a scalar at the group's order" in
    assert(
      mul.run(bytesOf(generator + word(groupOrder))).map(_.toHex) == Right(infinity),
      "the generator has exactly this order, so this many of it is nothing"
    )

  it should "answer the point itself for a scalar one above the group's order" in
    assert(
      mul.run(bytesOf(generator + word(groupOrder + 1))).map(_.toHex) == Right(generator),
      "EIP-196 admits a scalar between the group's order and the field's, so it is reduced rather than refused"
    )

  it should "answer for the largest scalar a word can carry" in
    assert(
      mul.run(bytesOf(generator + "ff" * 32)).isRight,
      "EIP-196: the scalar can be any number between 0 and 2**256-1"
    )

  // ── the boundary between a coordinate and the modulus ───────────────────

  // Each of the two below is a PAIR: an encoding at or above the modulus, and
  // the same encoding with one modulus taken off it. The two differ by exactly
  // `p`, so an implementation that reduced instead of refusing would answer
  // alike for both -- which is what makes these a boundary rather than two
  // inputs that happen to fail.
  "a coordinate at the field modulus" should "be refused rather than reduced to the point at infinity" in
    assert(
      add.run(bytesOf(word(fieldModulus) + zeroWord + infinity)) == Left(Halt.InvalidParameter) &&
        add.run(bytesOf(zeroWord + zeroWord + infinity)).map(_.toHex) == Right(infinity),
      "EIP-197: an encoding value of p or larger is invalid, and p reduces to the encoding that is admitted"
    )

  "a coordinate above the field modulus" should "be refused rather than reduced to a point on the curve" in
    assert(
      add.run(bytesOf(word(BigInt(1)) + word(fieldModulus + 2) + infinity)) == Left(Halt.InvalidParameter) &&
        add.run(bytesOf(word(BigInt(1)) + word(BigInt(2)) + infinity)).map(_.toHex) == Right(generator),
      "EIP-196 lists points which would be valid if the numbers were taken mod p as a case that should fail"
    )

  // ── the rule EIP-197 adds that EIP-196 has no equivalent of ─────────────

  "a point of the second group" should "be refused where it is on the curve and not of the group's order" in
    assert(
      pairing.run(bytesOf(infinity + offSubgroup)) == Left(Halt.InvalidParameter),
      "EIP-197 requires the order of a G_2 element to be checked against the group order"
    )

  it should "be admitted where it is on the same curve and IS of the group's order" in
    assert(
      pairing.run(bytesOf(infinity + secondGenerator)).map(_.toHex) == Right(word(BigInt(1))),
      "or the refusal above is of something other than the order"
    )

  // ── what the pairing answers, at the two ends ───────────────────────────

  "a pairing check" should "answer one for an input of no bytes" in
    assert(
      pairing.run(Bytes.Empty).map(_.toHex) == Right(word(BigInt(1))),
      "EIP-197: empty input is valid and results in returning one"
    )

  it should "answer one for a pair whose first point is at infinity" in
    assert(
      pairing.run(bytesOf(infinity + secondGenerator)).map(_.toHex) == Right(word(BigInt(1))),
      "a pair with nothing in it contributes the identity to the product"
    )

  it should "answer zero for the two generators paired once" in
    assert(
      pairing.run(bytesOf(generator + secondGenerator)).map(_.toHex) == Right(zeroWord),
      "one generator against the other is not the identity, which is what makes the pairing non-degenerate"
    )

  // ── a refusal is a halt, and it is not the halt a shortfall produces ────

  "a refusal" should "be an exceptional halt rather than an empty answer" in
    assert(
      add.run(bytesOf(word(fieldModulus) + zeroWord)).isLeft,
      "collapsing a refusal into no bytes would make a failed call look like a successful one"
    )

  it should "name the input rather than the gas" in
    assert(
      add.run(bytesOf(word(fieldModulus) + zeroWord)) == Left(Halt.InvalidParameter),
      "the charge was settled before this ran, so running out of gas is not what happened"
    )

  it should "consume everything the invocation had, where an invocation reaches one" in {
    val refusing = EvmFixtures.precompiles.adding(PrecompileSet.AltBn128Add, add)
    val environment = EvmFixtures.environment(withPrecompiles = refusing)
    val frame = new Frame(
      EvmFixtures.message(
        currentTarget = PrecompileSet.AltBn128Add,
        data = bytesOf(word(fieldModulus) + zeroWord),
        transfersValue = false
      ),
      Code(Bytes.Empty),
      BigInt(100000)
    )
    assert(
      Interpreter.run(frame, environment) == Right(Outcome.Halted(Halt.InvalidParameter)) &&
        frame.gasLeft == BigInt(0),
      "an exceptional halt keeps nothing, and a refusal is one"
    )
  }

  it should "leave the remainder alone where the same invocation is answered" in {
    val answering = EvmFixtures.precompiles.adding(PrecompileSet.AltBn128Add, add)
    val environment = EvmFixtures.environment(withPrecompiles = answering)
    val frame = new Frame(
      EvmFixtures.message(
        currentTarget = PrecompileSet.AltBn128Add,
        data = bytesOf(generator + infinity),
        transfersValue = false
      ),
      Code(Bytes.Empty),
      BigInt(100000)
    )
    assert(
      Interpreter.run(frame, environment).map(_.isInstanceOf[Outcome.Stopped]) == Right(true) &&
        frame.gasLeft == BigInt(100000) - add.gasFor(Bytes.Empty),
      "or the zeroing above is what every invocation of a precompile does rather than what a refusal does"
    )
  }
