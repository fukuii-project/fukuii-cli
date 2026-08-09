package org.fukuii.crypto

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import scala.util.control.NonFatal

/** ECDSA verification over secp256r1, the NIST P-256 curve.
  *
  * Verification only, and that is the whole surface rather than a first
  * instalment: this curve is here to CHECK signatures produced elsewhere —
  * by a passkey, a secure enclave, a hardware key — so nothing in this client
  * signs with it and no recovery path is defined for it. That is the opposite
  * of [[Secp256k1]], where recovery is the point.
  */
object Secp256r1:

  private val params = SECNamedCurves.getByName("secp256r1")
  private val domain = ECDomainParameters(params.getCurve, params.getG, params.getN, params.getH)

  /** Verifies `(r, s)` over `messageHash` for the public key `(x, y)`.
    *
    * Every rejection is a `false` rather than an exception, including a point
    * that is not on the curve and a coordinate outside the field. Those arrive
    * from outside and are ordinary invalid input, not an error in this node.
    */
  def verify(messageHash: IArray[Byte], r: BigInt, s: BigInt, x: BigInt, y: BigInt): Boolean =
    val n = domain.getN
    // Rejected before touching the curve: these are cheap, and a zero or
    // out-of-range scalar is the classic accept-anything hole.
    if r.signum <= 0 || s.signum <= 0 then false
    else if r.bigInteger.compareTo(n) >= 0 || s.bigInteger.compareTo(n) >= 0 then false
    else
      try
        val point = domain.getCurve.createPoint(x.bigInteger, y.bigInteger)
        // Defence in depth, and REDUNDANT against this provider version:
        // deleting this line changes no outcome across the whole corpus,
        // including its 46 point-at-infinity and off-curve cases, so the
        // provider is already rejecting them somewhere below. Kept because
        // which layer enforces it is the provider's choice and not a contract,
        // and left honest rather than described as the thing doing the work.
        if !point.isValid then false
        else
          val signer = ECDSASigner()
          signer.init(false, ECPublicKeyParameters(point, domain))
          signer.verifySignature(mutableCopy(messageHash), r.bigInteger, s.bigInteger)
      catch case NonFatal(_) => false

  /** Verifies the 160-byte concatenation used by the P256VERIFY precompile:
    * `messageHash || r || s || x || y`, each field exactly 32 bytes.
    */
  def verifyPacked(input: IArray[Byte]): Boolean =
    if input.length != 160 then false
    else
      def word(index: Int): BigInt =
        BigInt(BigInteger(1, mutableCopy(input.slice(index * 32, index * 32 + 32))))
      verify(input.slice(0, 32), word(1), word(2), word(3), word(4))
