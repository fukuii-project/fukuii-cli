package org.fukuii.crypto

import org.bouncycastle.crypto.digests.RIPEMD160Digest

/** RIPEMD-160, the 160-bit member of the RIPEMD family.
  *
  * ==Bytes rather than a named type, because 160 bits is nothing else here==
  *
  * The digest is 20 bytes wide, which is also [[org.fukuii.bytes.Address]]'s
  * width -- and it is not an address. Returning one would make a transposition
  * with a real account compile, so this hands back the bytes and leaves naming
  * them to whatever asked. The 256-bit digests in this module return
  * [[org.fukuii.bytes.Hash]] because that type is exactly "32 bytes" and
  * carries no other meaning.
  *
  * The provider supplies this one; the family's other widths -- 128, 256, 320 --
  * are separate classes and none of them is a parameterization of this, so a
  * wrong choice is a wrong import rather than a wrong argument.
  */
object Ripemd160:

  /** How wide the digest is, in bytes. */
  val Width: Int = 20

  def hash(input: IArray[Byte]): IArray[Byte] =
    val digest = new RIPEMD160Digest()
    val buffer = mutableCopy(input)
    digest.update(buffer, 0, buffer.length)
    val out = new Array[Byte](digest.getDigestSize)
    val _ = digest.doFinal(out, 0)
    IArray.unsafeFromArray(out)
