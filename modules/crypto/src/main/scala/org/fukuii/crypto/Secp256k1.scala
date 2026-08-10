package org.fukuii.crypto

import java.math.BigInteger
import org.fukuii.bytes.Hash
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import org.bouncycastle.math.ec.ECAlgorithms
import org.bouncycastle.math.ec.FixedPointCombMultiplier
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
  *
  * ==The message hash is a type, so a wrong width is unwritable==
  *
  * `sign`, `verify` and `recoverPublicKey` take [[org.fukuii.bytes.Hash]]
  * rather than a byte sequence, and that is a correctness boundary rather than
  * a convenience.
  *
  * Given an arbitrary-width input the three do NOT derive the same value from
  * it: `sign` and `verify` route through the provider, which truncates to the
  * leftmost bits of the curve order, while `recoverPublicKey` takes the whole
  * integer and reduces it — on a 48-byte input the two disagree. A caller with
  * a wrong-width digest would get a signature that verifies here and nowhere
  * else, with no signal at the call site.
  *
  * Taking the type removes the input that causes it instead of checking for it,
  * so the divergence has no reachable case rather than a guarded one.
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
  def sign(messageHash: Hash, privateKey: BigInt): Option[Signature] =
    // The key must lie in [1, n-1]. The provider throws rather than returning
    // for a key outside it — measured against the pinned version for 0, -1 and
    // n — and this returns an Option, so the guard is what makes that signature
    // honest rather than aspirational.
    if privateKey.signum <= 0 || privateKey.bigInteger.compareTo(domain.getN) >= 0 then None
    else
      try
        val bytes  = mutableCopy(messageHash.toBytes)
        val e      = BigInteger(1, bytes)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(privateKey.bigInteger, domain))
        val out  = signer.generateSignature(bytes)
        val r    = out(0)
        val rawS = out(1)
        val s    = if rawS.compareTo(halfCurveOrder) > 0 then domain.getN.subtract(rawS) else rawS
        publicKeyOf(privateKey).flatMap(expected =>
          recoveryIdFor(e, r, s, expected).map(id => Signature(BigInt(r), BigInt(s), id))
        )
      catch case NonFatal(_) => None

  /** Verifies against a public key in any encoding the curve admits.
    *
    * `publicKey` may be uncompressed (65 bytes, `0x04`), compressed (33 bytes,
    * `0x02`/`0x03`) or hybrid (65 bytes, `0x06`/`0x07`) — the provider's point
    * decoder accepts all three and this does not narrow it. A caller holding
    * exactly one of those forms is not obliged to convert, and one holding
    * attacker-supplied bytes should know that three shapes reach here.
    *
    * The `r` and `s` bounds are the provider's rather than this module's, which
    * is an asymmetry with the sibling curve — that one checks them itself. The
    * provider was confirmed to perform the check, so this is a difference in
    * where the guard lives and not a gap in whether one runs.
    */
  def verify(publicKey: IArray[Byte], messageHash: Hash, signature: Signature): Boolean =
    try
      val point  = domain.getCurve.decodePoint(mutableCopy(publicKey))
      val signer = ECDSASigner()
      signer.init(false, ECPublicKeyParameters(point, domain))
      signer.verifySignature(mutableCopy(messageHash.toBytes), signature.r.bigInteger, signature.s.bigInteger)
    catch case NonFatal(_) => false

  /** Recovers the uncompressed public key that produced a signature.
    *
    * This is what the EVM's `ecrecover` rests on, so a wrong answer is a
    * consensus fault rather than a local error. `None` for any input the curve
    * rejects; the caller decides what an unrecoverable signature means.
    */
  def recoverPublicKey(messageHash: Hash, signature: Signature): Option[IArray[Byte]] =
    val n     = domain.getN
    val r     = signature.r.bigInteger
    val s     = signature.s.bigInteger
    val recId = signature.recoveryId
    // r and s must lie in [1, n-1]. The upper bound is NOT implied by the
    // `x < prime` test below: the field prime exceeds the curve order, so an r
    // in [n, p) passes that test, and s is never compared to anything without
    // this line — `s.mod(n)` downstream would silently accept a reduced value.
    // The specification requires it outright -- the Yellow Paper's ECREC
    // returns 0 when r or s is zero or at least the curve order,
    // `ethereum/yellowpaper, master, efc5f9a1 (2025-02-04)` -- and both halves
    // of the reference pair enforce it: go-ethereum's ValidateSignatureValues
    // and besu's SECPSignature.checkInBounds. The sibling curve in this module
    // already did.
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
            val e       = BigInteger(1, mutableCopy(messageHash.toBytes))
            val rInv    = r.modInverse(n)
            val srInv   = rInv.multiply(s).mod(n)
            val eInvInv = rInv.multiply(n.subtract(e.mod(n)).mod(n)).mod(n)
            val q = ECAlgorithms.sumOfTwoMultiplies(domain.getG, eInvInv, pointR, srInv)
            // The recovered point can be the point at infinity, and this
            // provider encodes that as the single byte 0x00 rather than
            // refusing. Returning it hands the caller a one-byte "public key":
            // a precompile hashing it computes the digest of the empty string
            // and derives a fixed, live-looking address, where every reference
            // client returns failure. Reachable on demand — pick any k, set
            // r = (kG).x and s = e/k, and both land inside [1, n-1].
            if q.isInfinity then None
            else Some(IArray.unsafeFromArray(q.normalize().getEncoded(false)))
      catch case NonFatal(_) => None

  /** The uncompressed public key for a private key, as 65 bytes.
    *
    * `None` for a key outside [1, n-1]. Those multiply to the point at
    * infinity, which this provider encodes as a single zero byte — so the
    * 65-byte contract would otherwise be one a caller could not rely on.
    */
  def publicKeyOf(privateKey: BigInt): Option[IArray[Byte]] =
    if privateKey.signum <= 0 || privateKey.bigInteger.compareTo(domain.getN) >= 0 then None
    else
      // A comb multiplier with a fixed access pattern, NOT the provider's
      // `referenceMultiply`, whose double-and-add branches once per set bit of
      // the scalar — so its timing and cache trace are a function of the
      // private key. The provider's own key generator uses this one.
      val point = FixedPointCombMultiplier().multiply(domain.getG, privateKey.bigInteger).normalize()
      if point.isInfinity then None
      else Some(IArray.unsafeFromArray(point.getEncoded(false)))

  /** Which recovery id reproduces this key, found by trying each.
    *
    * Deriving it in closed form needs the nonce, which the deterministic
    * generator does not hand back, so signing recovers it the way a verifier
    * would.
    */
  private def recoveryIdFor(e: BigInteger, r: BigInteger, s: BigInteger, expected: IArray[Byte]): Option[Int] =
    val candidate = Signature(BigInt(r), BigInt(s), 0)
    // leftPad32 returns exactly 32 bytes, so the truncating constructor is
    // total here and the checked one would add a branch nothing can reach.
    val hash      = Hash.fromBytesTruncating(IArray.unsafeFromArray(leftPad32(e)))
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
