package org.fukuii.crypto

import org.fukuii.bytes.Hex
import org.scalatest.flatspec.AnyFlatSpec

/** Certification against go-ethereum's published secp256k1 vector, from
  * `crypto/signature_test.go` @ `7a1b11564c16f54dff0a2f578179c482d9f701bf` —
  * the message, signature and both key encodings its own `TestEcrecover` and
  * `TestVerifySignature` run on.
  *
  * Recovery is the piece to watch. The provider supplies the curve and ECDSA
  * but not recovery, so that arithmetic is this module's own; it either
  * reproduces this exact key or it does not, which is why a published vector
  * rather than a round trip is what certifies it. A sign-then-recover round
  * trip would agree with a consistently wrong implementation.
  */
class Secp256k1Spec extends AnyFlatSpec:

  private def hex(s: String): IArray[Byte] = Hex.decode(s).toOption.get

  private val message = hex("ce0677bb30baa8cf067c88db9811f4333d131bf8bcf12fe7065d211dce971008")

  private val signature = Signature(
    r = BigInt("90f27b8b488db00b00606796d2987f6a5f59ae62ea05effe84fef5b8b0e54998", 16),
    s = BigInt("4a691139ad57a3f0b906637673aa2f63d1f55cb1a69199d4009eea23ceaddc93", 16),
    recoveryId = 1
  )

  private val publicKeyHex =
    "04e32df42865e97135acfb65f3bae71bdc86f4d49150ad6a440b6f15878109880a" +
      "0a2b2667f7e725ceea70c673093bf67663e0312623c8e091b13cf2c0f11ef652"

  private val compressedKeyHex =
    "02e32df42865e97135acfb65f3bae71bdc86f4d49150ad6a440b6f15878109880a"

  "recoverPublicKey" should "reproduce the reference client's published key" in {
    val recovered = Secp256k1.recoverPublicKey(message, signature).map(Hex.encode)
    assert(recovered.contains(publicKeyHex), "ecrecover must return the published uncompressed key")
  }

  it should "return a different key for a different recovery id" in {
    val other = Secp256k1.recoverPublicKey(message, signature.copy(recoveryId = 0)).map(Hex.encode)
    assert(!other.contains(publicKeyHex), "a wrong recovery id must not yield the right key")
  }

  it should "reject a recovery id outside 0..3" in {
    assert(Secp256k1.recoverPublicKey(message, signature.copy(recoveryId = 4)).isEmpty, "4 is not a recovery id")
  }

  it should "reject a zero r" in {
    assert(Secp256k1.recoverPublicKey(message, signature.copy(r = BigInt(0))).isEmpty, "r must be positive")
  }

  "verify" should "accept the published signature under the uncompressed key" in {
    assert(Secp256k1.verify(hex(publicKeyHex), message, signature), "the published signature must verify")
  }

  it should "accept it under the compressed key" in {
    assert(Secp256k1.verify(hex(compressedKeyHex), message, signature), "both key encodings name the same point")
  }

  it should "reject a signature against the wrong message" in {
    val otherMessage = Keccak256.hash(IArray.empty[Byte]).toBytes
    assert(!Secp256k1.verify(hex(publicKeyHex), otherMessage, signature), "a signature is bound to its message")
  }

  it should "reject a malformed public key rather than throwing" in {
    assert(!Secp256k1.verify(hex("0400"), message, signature), "an undecodable key is a false, not an exception")
  }

  "sign" should "produce a signature that verifies under the matching key" in {
    val key    = BigInt("4646464646464646464646464646464646464646464646464646464646464646", 16)
    val signed = Secp256k1.sign(message, key)
    assert(
      signed.exists(sig => Secp256k1.verify(Secp256k1.publicKeyOf(key), message, sig)),
      "a signature this module produced must verify under the key that made it"
    )
  }

  it should "be deterministic, so the same key and message sign identically" in {
    val key = BigInt("4646464646464646464646464646464646464646464646464646464646464646", 16)
    assert(
      Secp256k1.sign(message, key) == Secp256k1.sign(message, key),
      "a deterministic nonce means signing twice gives one answer"
    )
  }

  it should "canonicalise s to the low half of the curve order" in {
    val key       = BigInt("4646464646464646464646464646464646464646464646464646464646464646", 16)
    val halfOrder = BigInt("7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0", 16)
    assert(Secp256k1.sign(message, key).exists(_.s <= halfOrder), "a high s is the same signature, reflected")
  }

  it should "recover the signer's own key from what it produced" in {
    val key    = BigInt("4646464646464646464646464646464646464646464646464646464646464646", 16)
    val signed = Secp256k1.sign(message, key)
    assert(
      signed.flatMap(sig => Secp256k1.recoverPublicKey(message, sig)).map(Hex.encode)
        == Some(Hex.encode(Secp256k1.publicKeyOf(key))),
      "the recovery id this module chose must be the one that works"
    )
  }
