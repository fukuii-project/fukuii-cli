package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The contract of the native that evaluates a committed polynomial, stated as
  * behavior rather than as a vector.
  *
  * [[KzgPropSpec]] carries the corpus and through it the ceremony output this
  * repository ships. What is here is what no vector in that corpus establishes:
  * the exact width rule, the versioned-hash comparison, the flat price, the
  * bytes a success answers with, and that both ways of failing are one halt
  * rather than an empty answer.
  *
  * Expected behavior is `ethereum/EIPs` @ `d2a64c2d4` (2026-09-11),
  * `EIPS/eip-4844.md`, Final, read against `ethereum/execution-specs` @
  * `0cc100eb1` (2026-09-11),
  * `src/ethereum/forks/cancun/vm/precompiled_contracts/point_evaluation.py`,
  * and against `ethereum/go-ethereum` @ `02872e9ef` (2026-09-11),
  * `core/vm/contracts.go`.
  */
class PointEvaluationPrecompileSpec extends AnyFlatSpec:

  private val price = BigInt(50000)

  private val native = Precompile.PointEvaluation(price)

  private def bytesOf(hex: String): Bytes = EvmFixtures.bytesOf(hex)

  /** One argument the corpus states a holding proof for, laid out the way the
    * precompile takes it.
    *
    * `verify_kzg_proof_case_correct_proof_05c1f3685f3393f0`, from the same
    * published file [[KzgPropSpec]] reads. **A commitment to the CONSTANT
    * polynomial two**, which is what makes it the right fixture here: the
    * commitment is a real point rather than infinity, `z` is a full-width value
    * with no structure, and `y` is small enough to read.
    *
    * ==Its degeneracy is the reason each mutation below is the one it is==
    *
    * A constant polynomial takes the same value at every point, so **moving `z`
    * does not falsify the proof** -- measured, not assumed. A test mutating `z`
    * would pass against a build that never verified anything, so the mutations
    * below move `y`, exchange the two, or break the commitment instead.
    */
  private val commitment =
    "a572cbea904d67468808c8eb50a9450c9721db309128012543902d0ac358a62ae28f75bb8f1c7c42c39a8c5529bf0f4e"

  private val proof = "c0" + "00" * 47

  private val z = "564c0a11a0f704f4fc3e8acfe0f8245f0ad1347b378fbf96e206da11a5d36306"

  private val y = "00" * 31 + "02"

  private val versionedHash = BlobGas.versionedHashOf(bytesOf(commitment)).toHex

  private def argument(hash: String, point: String, value: String, key: String, witness: String): Bytes =
    bytesOf(hash + point + value + key + witness)

  private val valid = argument(versionedHash, z, y, commitment, proof)

  /** What the document says a verification that holds answers with: the
    * field-element count and the field's order, one word each.
    *
    * Written here as `ethereum/go-ethereum` @ `02872e9ef` publishes it --
    * `core/vm/contracts.go:1551`, `blobPrecompileReturnValue` -- rather than
    * derived, so that this and the implementation reach the same 64 bytes by
    * two different routes. The implementation concatenates two constants read
    * off the native library; this is a client's literal. A disagreement between
    * the library and the document would show up here and nowhere else.
    */
  private val statedAnswer =
    bytesOf(
      "0000000000000000000000000000000000000000000000000000000000001000" +
        "73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001"
    )

  /** A commitment the library declines outright rather than disproves.
    *
    * Forty-eight bytes of `ff` name no compressed point of the group.
    */
  private val notAPoint = "ff" * 48

  private val derivedHash = BlobGas.versionedHashOf(bytesOf(commitment)).toBytes

  private val commitmentDigest = org.fukuii.crypto.Sha256.hash(bytesOf(commitment).toIArray).toBytes

  "PointEvaluation" should "answer the two field parameters when the proof holds" in
    assert(native.run(valid) == Right(statedAnswer), "a holding proof must answer the document's constant")

  it should "charge its flat price whatever it is asked" in {
    val prices = Seq(Bytes.Empty, valid, bytesOf("00"), bytesOf("ff" * 512)).map(native.gasFor)
    assert(prices.distinct == Seq(price), "the charge must not vary with the input; got " + prices.toString)
  }

  /** The width rule, which is an equality and not a minimum.
    *
    * *"if len(data) != 192"*, so one byte either side is refused. The longer
    * case is the one worth asserting: every other native here reads a
    * fixed-width prefix and ignores the rest, so a reader carrying that habit
    * would expect a padded argument to succeed on its prefix.
    */
  it should "refuse an argument one byte short of the exact width" in
    assert(
      native.run(bytesOf(valid.toHex.dropRight(2))) == Left(Halt.InvalidParameter),
      "191 bytes must be refused"
    )

  it should "refuse a valid argument with a trailing byte, rather than reading its prefix" in
    assert(
      native.run(bytesOf(valid.toHex + "00")) == Left(Halt.InvalidParameter),
      "193 bytes must be refused, not truncated to the 192 that would have verified"
    )

  it should "refuse an empty argument" in
    assert(native.run(Bytes.Empty) == Left(Halt.InvalidParameter), "an empty argument is not 192 bytes")

  /** The versioned hash must be the one the commitment actually produces.
    *
    * Mutating the LAST byte rather than the first is deliberate: the first byte
    * is the scheme discriminator, and a build that checked only that would
    * still be wrong about the other thirty-one. This mutation leaves the scheme
    * byte correct, so only a full comparison catches it.
    */
  it should "refuse a versioned hash whose digest half does not match the commitment" in {
    val mutated = versionedHash.dropRight(2) + (if versionedHash.endsWith("00") then "01" else "00")
    assert(
      native.run(argument(mutated, z, y, commitment, proof)) == Left(Halt.InvalidParameter),
      "the whole hash must be compared, not only its scheme byte"
    )
  }

  it should "refuse a versioned hash carrying a scheme byte the rules do not know" in
    assert(
      native.run(argument("02" + versionedHash.drop(2), z, y, commitment, proof)) == Left(Halt.InvalidParameter),
      "a hash under another scheme must be refused"
    )

  /** A proof that does not hold, over arguments that are every bit well-formed.
    *
    * The claimed value moves and nothing else: the commitment still hashes to
    * the stated versioned hash, every width is right, and both points are real
    * -- so this reaches the verification and fails there. **It is the only case
    * here that separates a build that verifies a proof from one that compares
    * the hash and stops**, which is the build P5 recorded that no transaction
    * corpus can tell apart.
    */
  it should "refuse a well-formed argument whose claimed value is wrong" in {
    val wrongValue = "00" * 31 + "03"
    assert(
      native.run(argument(versionedHash, z, wrongValue, commitment, proof)) == Left(Halt.InvalidParameter),
      "a claimed value the polynomial does not take must be refused"
    )
  }

  /** The argument order, which four byte strings make transposable.
    *
    * `z` and `y` are the same width, so exchanging them type-checks, runs, and
    * answers. Measured against this fixture: the exchange verifies as `false`,
    * so the transposition is caught -- and it is caught precisely because `y`
    * is not a value the polynomial takes at the point `z` names.
    */
  it should "refuse the same argument with its point and claimed value exchanged" in
    assert(
      native.run(argument(versionedHash, y, z, commitment, proof)) == Left(Halt.InvalidParameter),
      "exchanging the point and the claimed value must not verify"
    )

  "a commitment that names no point" should "be declined by the primitive rather than disproved" in
    assert(
      Kzg.verifyProof(
        bytesOf(notAPoint).toIArray,
        bytesOf(z).toIArray,
        bytesOf(y).toIArray,
        bytesOf(proof).toIArray
      ) == None,
      "the fixture for the test below must be an argument the primitive declines"
    )

  /** The collapse the precompile makes and the primitive does not.
    *
    * `org.fukuii.evm.Kzg` reports a declined argument apart from a disproved
    * one; this must not. Both are an exceptional halt keeping nothing, which is
    * what the specification and both clients do.
    */
  it should "halt exactly as a failed proof halts" in {
    val hash = BlobGas.versionedHashOf(bytesOf(notAPoint)).toHex
    assert(
      native.run(argument(hash, z, y, notAPoint, proof)) == Left(Halt.InvalidParameter),
      "a declined argument halts exactly as a failed proof does"
    )
  }

  "versionedHashOf" should "lead with the scheme byte" in
    assert(derivedHash.head == BlobGas.VersionedHashVersion, "the first byte names the scheme")

  it should "carry the digest's remaining thirty-one bytes unchanged" in
    assert(derivedHash.tail.sameElements(commitmentDigest.tail), "the rest is the digest's own")

  /** Without this the test above is satisfied by a derivation that copies the
    * digest whole, since only the first byte distinguishes the two.
    */
  it should "overwrite a digest byte that is not already the scheme byte" in
    assert(
      commitmentDigest.head != BlobGas.VersionedHashVersion,
      "this fixture's digest must not already begin with the scheme byte, or the rule is untested"
    )
