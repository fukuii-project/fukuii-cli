package org.fukuii.crypto

import java.math.BigInteger
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import scala.util.control.NonFatal

/** An ECDSA signature over secp256k1.
  *
  * `recoveryId` is the extra information that makes public-key recovery
  * possible: a signature alone matches two points, and this selects which.
  * Stored 0..3 — the value the curve math uses — never the 27-offset form that
  * appears on the wire and in JSON, so that no arithmetic in this module has to
  * guess which convention a value is in.
  */
final case class Signature(r: BigInt, s: BigInt, recoveryId: Int)

/** secp256k1 signing, verification and public-key recovery.
  *
  * The provider supplies the curve and ECDSA; recovery is not part of that API
  * and the arithmetic below is this module's own, which is why it is certified
  * against a reference client's published vector rather than reviewed by eye.
  */
object Secp256k1:

  private val params = SECNamedCurves.getByName("secp256k1")
  private val domain = ECDomainParameters(params.getCurve, params.getG, params.getN, params.getH)

  /** Signatures with `s` above this are the same signature reflected, so
    * accepting both makes a signed payload malleable — its hash changes while
    * it stays valid. Signing canonicalizes to the low half.
    */
  private val halfCurveOrder = params.getN.shiftRight(1)

  /** Signs with a deterministic nonce and a canonical low `s`.
    *
    * The nonce is derived from the key and the message rather than drawn from a
    * random source: a repeated or predictable nonce discloses the private key
    * outright, and determinism removes the runtime's entropy from that failure
    * path.
    */
  def sign(messageHash: IArray[Byte], privateKey: BigInt): Option[Signature] =
    // The key must lie in [1, n-1]. The provider throws rather than returning
    // for a key outside it — measured against the pinned version for 0, -1 and
    // n — and this returns an Option, so the guard is what makes that signature
    // honest rather than aspirational.
    if privateKey.signum <= 0 || privateKey.bigInteger.compareTo(domain.getN) >= 0 then None
    else
      try
        val bytes  = mutableCopy(messageHash)
        val e      = BigInteger(1, bytes)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey.bigInteger, domain))
        val out  = signer.generateSignature(bytes)
        val r    = out(0)
        val rawS = out(1)
        val s    = if rawS.compareTo(halfCurveOrder) > 0 then domain.getN.subtract(rawS) else rawS
        recoveryIdFor(e, r, s, publicKeyOf(privateKey)).map(id => Signature(BigInt(r), BigInt(s), id))
      catch case NonFatal(_) => None

  def verify(publicKey: IArray[Byte], messageHash: IArray[Byte], signature: Signature): Boolean =
    try
      val point  = domain.getCurve.decodePoint(mutableCopy(publicKey))
      val signer = ECDSASigner()
      signer.init(false, ECPublicKeyParameters(point, domain))
      signer.verifySignature(mutableCopy(messageHash), signature.r.bigInteger, signature.s.bigInteger)
    catch case NonFatal(_) => false

  /** Recovers the uncompressed public key that produced a signature.
    *
    * This is what the EVM's `ecrecover` rests on, so a wrong answer is a
    * consensus fault rather than a local error. `None` for any input the curve
    * rejects; the caller decides what an unrecoverable signature means.
    */
  def recoverPublicKey(messageHash: IArray[Byte], signature: Signature): Option[IArray[Byte]] =
    val n     = domain.getN
    val r     = signature.r.bigInteger
    val s     = signature.s.bigInteger
    val recId = signature.recoveryId
    // r and s must lie in [1, n-1]. The upper bound is NOT implied by the
    // `x < prime` test below: the field prime exceeds the curve order, so an r
    // in [n, p) passes that test, and s is never compared to anything without
    // this line — `s.mod(n)` downstream would silently accept a reduced value.
    // The reference implementation requires both unconditionally, and the
    // sibling curve in this module already did.
    if recId < 0 || recId > 3 then None
    else if r.signum <= 0 || s.signum <= 0 then None
    else if r.compareTo(n) >= 0 || s.compareTo(n) >= 0 then None
    else
      try
        // The x coordinate of R. recId's high bit selects which candidate x,
        // its low bit which of the two y values for that x.
        val prime = domain.getCurve.getField.getCharacteristic
        val x     = r.add(BigInteger.valueOf((recId / 2).toLong).multiply(n))
        if x.compareTo(prime) >= 0 then None
        else
          val encoded = Array((0x02 + (recId & 1)).toByte) ++ leftPad32(x)
          val pointR  = domain.getCurve.decodePoint(encoded)
          if !pointR.multiply(n).isInfinity then None
          else
            val e       = BigInteger(1, mutableCopy(messageHash))
            val rInv    = r.modInverse(n)
            val srInv   = rInv.multiply(s).mod(n)
            val eInvInv = rInv.multiply(n.subtract(e.mod(n)).mod(n)).mod(n)
            val q       = ECAlgorithms.sumOfTwoMultiplies(domain.getG, eInvInv, pointR, srInv)
            Some(IArray.unsafeFromArray(q.normalize().getEncoded(false)))
      catch case NonFatal(_) => None

  /** The uncompressed public key for a private key, as 65 bytes. */
  def publicKeyOf(privateKey: BigInt): IArray[Byte] =
    val point = ECAlgorithms.referenceMultiply(domain.getG, privateKey.bigInteger).normalize()
    IArray.unsafeFromArray(point.getEncoded(false))

  /** Which recovery id reproduces this key, found by trying each.
    *
    * Deriving it in closed form needs the nonce, which the deterministic
    * generator does not hand back, so signing recovers it the way a verifier
    * would.
    */
  private def recoveryIdFor(e: BigInteger, r: BigInteger, s: BigInteger, expected: IArray[Byte]): Option[Int] =
    val candidate = Signature(BigInt(r), BigInt(s), 0)
    val hash      = IArray.unsafeFromArray(leftPad32(e))
    (0 to 3).find { id =>
      recoverPublicKey(hash, candidate.copy(recoveryId = id))
        .exists(found => ConstantTime.equal(found, expected))
    }

  private def leftPad32(value: BigInteger): Array[Byte] =
    val raw = value.toByteArray
    val out = new Array[Byte](32)
    val src = if raw.length > 32 then raw.length - 32 else 0
    val len = raw.length - src
    System.arraycopy(raw, src, out, 32 - len, len)
    out
