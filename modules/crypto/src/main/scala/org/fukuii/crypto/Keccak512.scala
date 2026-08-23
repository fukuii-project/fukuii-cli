package org.fukuii.crypto

import org.bouncycastle.crypto.digests.KeccakDigest

/** The 512-bit Keccak digest.
  *
  * The same construction as [[Keccak256]] at a wider rate, and the same warning
  * applies with the same force: this is the ORIGINAL Keccak and not SHA-3, the
  * two differ only in their padding, and both accept every input and return 64
  * bytes while agreeing on none of them. `KeccakDigest` is the original;
  * `SHA3Digest` is not.
  *
  * ==Why the return type is not a named value==
  *
  * [[org.fukuii.bytes.Hash]] is 32 bytes by construction and there is no
  * 64-byte counterpart, because nothing in this project's domain carries one as
  * a value. The one consumer treats the output as a working buffer rather than
  * as an identity -- it is exclusive-ored, sliced into little-endian words and
  * fed back in -- so a wrapper would be unwrapped at every use.
  */
object Keccak512:

  /** How many bytes this digest returns. */
  val Width: Int = 64

  def hash(input: IArray[Byte]): IArray[Byte] =
    val digest = new KeccakDigest(512)
    val buffer = mutableCopy(input)
    digest.update(buffer, 0, buffer.length)
    val out = new Array[Byte](digest.getDigestSize)
    val _ = digest.doFinal(out, 0)
    IArray.unsafeFromArray(out)
