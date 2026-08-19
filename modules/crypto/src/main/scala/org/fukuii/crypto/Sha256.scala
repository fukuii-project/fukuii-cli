package org.fukuii.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.fukuii.bytes.Hash

/** SHA-256, the 256-bit member of the SHA-2 family.
  *
  * ==Three functions produce 32 bytes from the same input and none of them
  * agree==
  *
  * SHA-256 is FIPS 180-4's SHA-2 construction. SHA3-256 and SHA-512/256 are
  * different functions with the same output width, and every one of the three
  * accepts any input and returns 32 plausible bytes -- so picking the wrong one
  * fails nowhere and produces a chain that agrees with nobody. `SHA256Digest`
  * is the one named here; `SHA3Digest` and `SHA512tDigest` are one import away.
  */
object Sha256:

  def hash(input: IArray[Byte]): Hash =
    val digest = new SHA256Digest()
    val buffer = mutableCopy(input)
    digest.update(buffer, 0, buffer.length)
    val out = new Array[Byte](digest.getDigestSize)
    val _ = digest.doFinal(out, 0)
    // 32 bytes by construction, so the total constructor is right: the checked
    // one would add an error branch that cannot be reached or tested.
    Hash.fromBytesTruncating(IArray.unsafeFromArray(out))
