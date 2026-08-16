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
    * ==The lengths are folded in rather than answered early==
    *
    * An unequal length does NOT return early. Returning on it is measurably
    * faster than comparing, so a caller probing with guesses of varying length
    * learns the secret's length in a logarithmic number of queries and then
    * attacks the content with that fixed — CWE-208's own worked example.
    *
    * Both implementations that matter here fold the length difference into the
    * accumulator and run the loop regardless, and neither returns early: the
    * platform's own `MessageDigest.isEqual` seeds its accumulator with the
    * length difference and then indexes the shorter array through a branchless
    * clamp, and the cryptography provider this project already depends on seeds
    * `expected.length ^ supplied.length` and pads the tail with a value that is
    * all ones by construction. The form below is the second of those.
    *
    * ==Why this reads the inputs directly instead of delegating==
    *
    * A provider's equivalent takes `Array[Byte]`, and the only way to reach it
    * from `IArray` is a copy — which would duplicate **secret** bytes into two
    * further arrays that live until collection and appear in any heap or core
    * dump taken in between. For a comparison whose entire purpose is not
    * leaking a secret, the copies cost more than the delegation is worth.
    *
    * The loop is the standard form: accumulate the difference with `|` and
    * decide once at the end. No early exit and no content-dependent branch, so
    * the time is a function of the lengths alone and never of where the two
    * first differ.
    */
  def equal(a: IArray[Byte], b: IArray[Byte]): Boolean =
    val shared = if a.length < b.length then a.length else b.length
    // Seeded with the length difference rather than checked before the loop:
    // equal lengths contribute zero, unequal lengths can never come back to
    // zero, and no branch is taken on the answer.
    var diff = a.length ^ b.length
    var i    = 0
    while i < shared do
      diff |= a(i) ^ b(i)
      i += 1
    // `x ^ ~x` is all ones for every x, so a trailing byte forces a mismatch
    // whatever it holds. This exists to keep the work proportional to the
    // longer input rather than to stop early once the lengths disagree.
    var j = shared
    while j < b.length do
      diff |= b(j) ^ ~b(j)
      j += 1
    diff == 0
