package org.fukuii.crypto

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
    *
    * ==Why this reads the inputs directly instead of delegating==
    *
    * A provider's equivalent takes `Array[Byte]`, and the only way to reach it
    * from `IArray` is a copy — which would duplicate **secret** bytes into two
    * further arrays that live until collection and appear in any heap or core
    * dump taken in between. For a comparison whose entire purpose is not
    * leaking a secret, the copies cost more than the delegation is worth.
    *
    * The loop is the standard form: accumulate the difference over the whole
    * length with `|`, and decide once at the end. No early exit and no
    * content-dependent branch, so the time is a function of the length alone.
    */
  def equal(a: IArray[Byte], b: IArray[Byte]): Boolean =
    if a.length != b.length then false
    else
      var diff = 0
      var i    = 0
      while i < a.length do
        diff |= a(i) ^ b(i)
        i += 1
      diff == 0
