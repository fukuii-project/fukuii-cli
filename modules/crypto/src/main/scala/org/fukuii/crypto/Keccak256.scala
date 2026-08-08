package org.fukuii.crypto

import org.bouncycastle.crypto.digests.KeccakDigest
import org.fukuii.bytes.Hash

/** The 256-bit Keccak digest, as the EVM uses it.
  *
  * This is the ORIGINAL Keccak, not SHA-3. The two differ only in their padding
  * and are otherwise the same construction, so they accept the same inputs and
  * both return 32 bytes — while producing different digests for every one of
  * them. Nothing about a wrong choice here fails loudly: it produces plausible
  * output and a chain that agrees with nobody. `KeccakDigest` is the original;
  * `SHA3Digest`, one import away and one letter different in the JDK's own
  * algorithm name, is not.
  */
object Keccak256:

  def hash(input: IArray[Byte]): Hash =
    val digest = new KeccakDigest(256)
    val buffer = mutableCopy(input)
    digest.update(buffer, 0, buffer.length)
    val out = new Array[Byte](digest.getDigestSize)
    val _   = digest.doFinal(out, 0)
    // 32 bytes by construction, so the total constructor is right: the checked
    // one would add an error branch that cannot be reached or tested.
    Hash.fromBytesTruncating(IArray.unsafeFromArray(out))
