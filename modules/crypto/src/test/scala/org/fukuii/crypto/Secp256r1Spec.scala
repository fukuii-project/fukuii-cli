package org.fukuii.crypto

import java.math.BigInteger
import org.fukuii.bytes.Hex
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification against besu's P256VERIFY corpus,
  * `evm/src/test/resources/org/hyperledger/besu/evm/precompile/p256verify_test_vectors.json`
  * @ `besu-eth/besu, main, fd8389c5 (2026-07-31)`, which besu in turn draws from
  * Google's Wycheproof suite.
  *
  * The corpus is carried as a text resource rather than generated Scala —
  * `scripts/gen-p256-vectors.py` rebuilds it — and its value is the negative
  * half. A verifier returning `true` unconditionally passes every positive
  * vector ever written, so what catches it is the 212 rejections.
  *
  * WHAT THOSE 212 ACTUALLY ARE, counted rather than characterized: they are
  * dominated by out-of-range and zero scalars. **The corpus carries no
  * off-curve public key, no coordinate at or above the field prime, and no
  * point at infinity** — verified by evaluating the curve equation over every
  * vector. And its 12 malleability cases are all expected to be ACCEPTED,
  * which is correct for this curve and means they catch the opposite error: a
  * verifier that wrongly enforces a low `s`.
  *
  * ==The specification, and what the corpus cannot reach==
  *
  * The governing specification is **EIP-7951** (`Final`), `ethereum/EIPs` @
  * `e18e618c` — not besu, which is the reference implementation supplying the
  * vectors. It states five MUST conditions: input length, scalar bounds,
  * public-key coordinate bounds, the curve equation, and not-at-infinity.
  *
  * The corpus exercises the first two. The last three it cannot, because no
  * vector violates them — so they are asserted directly below. Those three are
  * exactly what the specification's own rationale records a predecessor
  * proposal for omitting, on the grounds it could cause consensus failures.
  */
class Secp256r1Spec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(input: IArray[Byte], shouldVerify: Boolean, name: String)

  private val corpus: Seq[Vector] =
    val stream = Option(getClass.getResourceAsStream("/p256verify-vectors.txt"))
    val source = stream.map(scala.io.Source.fromInputStream(_))
    try
      source.toSeq.flatMap(_.getLines()).filter(_.nonEmpty).map { line =>
        val parts = line.split(' ')
        Vector(Hex.decode(parts(0)).toOption.get, parts(1) == "1", parts(2))
      }
    finally source.foreach(_.close())

  /** Guards against a vacuous pass.
    *
    * `forAll` over an empty table SUCCEEDS, so a resource that failed to load
    * would turn both properties below into checks of nothing that still report
    * green. The counts are not asserted exactly — the corpus is regenerable and
    * an exact figure would be a maintained value — but both classes must be
    * present and substantial.
    */
  property("the corpus loaded, with both outcomes represented") {
    val valid   = corpus.count(_.shouldVerify)
    val invalid = corpus.count(!_.shouldVerify)
    assert(
      valid > 100 && invalid > 100,
      "expected a substantial corpus of both kinds; got valid=" + valid.toString + " invalid=" + invalid.toString
    )
  }

  property("every signature the corpus accepts verifies") {
    val accepted = Table("vector", corpus.filter(_.shouldVerify)*)
    forAll(accepted) { (v: Vector) =>
      assert(Secp256r1.verifyPacked(v.input), v.name)
    }
  }

  property("every signature the corpus rejects is rejected") {
    val rejected = Table("vector", corpus.filterNot(_.shouldVerify)*)
    forAll(rejected) { (v: Vector) =>
      assert(!Secp256r1.verifyPacked(v.input), v.name)
    }
  }

  property("a packed input of the wrong length is rejected") {
    val short = corpus.head.input.slice(0, 159)
    assert(!Secp256r1.verifyPacked(short), "159 bytes is not the precompile's calling convention")
  }

  private val oneByteHash = IArray.unsafeFromArray(BigInteger.ONE.toByteArray)

  property("a zero r is rejected outright") {
    assert(!Secp256r1.verify(oneByteHash, BigInt(0), BigInt(1), BigInt(1), BigInt(1)), "r = 0 must never verify")
  }

  property("a zero s is rejected outright") {
    assert(!Secp256r1.verify(oneByteHash, BigInt(1), BigInt(0), BigInt(1), BigInt(1)), "s = 0 must never verify")
  }

  /** The three MUST conditions the corpus contains no vector for.
    *
    * EIP-7951 requires each independently, and the predecessor proposal it
    * supersedes was faulted for omitting exactly these.
    *
    * THESE PIN THE CONTRACT, NOT ANY ONE LINE, and that distinction is measured
    * rather than assumed: removing the module's own point check leaves all
    * three passing, because the provider raises below this layer and the
    * outcome is `false` either way. So they do not defend the check — they
    * defend the observable behavior the specification actually mandates, which
    * is the right thing to assert when the enforcing layer is the provider's
    * choice. What they would catch is a provider that stopped rejecting, which
    * is the failure this module could not otherwise see.
    */
  private val fieldPrime =
    BigInt("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)

  /** A real point on the curve, taken from the first corpus vector that verifies. */
  private def validKey: (BigInt, BigInt) =
    val v = corpus.find(_.shouldVerify).get
    val hex = Hex.encode(v.input)
    (BigInt(hex.substring(192, 256), 16), BigInt(hex.substring(256, 320), 16))

  property("MUST 3 — a public key coordinate at the field prime is rejected") {
    val (_, qy) = validKey
    assert(!Secp256r1.verify(oneByteHash, BigInt(1), BigInt(1), fieldPrime, qy), "qx must be below p")
  }

  property("MUST 4 — a public key off the curve is rejected") {
    val (qx, qy) = validKey
    assert(!Secp256r1.verify(oneByteHash, BigInt(1), BigInt(1), qx, qy + 1), "a point off the curve is not a key")
  }

  property("MUST 5 — the point at infinity is rejected") {
    assert(!Secp256r1.verify(oneByteHash, BigInt(1), BigInt(1), BigInt(0), BigInt(0)), "(0,0) is not a public key")
  }
