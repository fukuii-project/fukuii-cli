package org.fukuii.crypto

import org.bouncycastle.util.Arrays as BcArrays

/** Comparisons whose duration does not depend on where two values first differ.
  *
  * One symbol, so that every later layer with a secret to compare — a message
  * authentication tag, a keystore check, a token — has somewhere to import from
  * rather than reaching for `==` and being right by accident.
  */
object ConstantTime:

  /** Compares two byte sequences without an early exit.
    *
    * `==` on the underlying arrays would compare identities; a hand-written
    * loop returns as soon as it finds a difference, and how long that took is
    * a measurement of how much of the secret the caller already had right.
    *
    * The LENGTHS are not secret and are not hidden: an unequal length answers
    * immediately, which is what every implementation of this does, because the
    * length is observable from the message anyway.
    */
  def equal(a: IArray[Byte], b: IArray[Byte]): Boolean =
    BcArrays.constantTimeAreEqual(mutableCopy(a), mutableCopy(b))
