package org.fukuii.evm

import org.scalatest.flatspec.AnyFlatSpec

/** What a top-level object's `lazy val` does on the access after its
  * initializer threw.
  *
  * ==Modeled on [[Kzg.trustedSetup]] rather than asked in the abstract==
  *
  * That value loads a native library and then a trusted setup, and both can
  * throw. The question this settles is whether a caller that catches the first
  * failure and asks again gets a SECOND attempt -- because the first half of
  * that initializer is not idempotent: `CKZG4844JNI.loadNativeLibrary` extracts
  * the platform library to a newly created temporary directory and `System.load`s
  * that path, so each attempt that reaches it leaves one directory and one
  * mapped library behind.
  *
  * **Neither the language reference's lazy-vals page nor the compiler's own
  * `LazyVals` runtime states the answer**, which is why it is measured here
  * rather than cited. The shape is a top-level `object` holding a `lazy val`,
  * because that is what `Kzg` is -- an instance field or a local would be a
  * different initialization strategy and so a different question.
  */
object ThrowingInitializer:

  var attempts: Int = 0

  lazy val value: Unit =
    attempts += 1
    throw new IllegalStateException("this initializer always fails")

class LazyInitializerRetrySpec extends AnyFlatSpec:

  it should "run the initializer again on the access after it threw" in {
    val first = intercept[IllegalStateException](ThrowingInitializer.value)
    val second = intercept[IllegalStateException](ThrowingInitializer.value)
    assert(
      ThrowingInitializer.attempts == 2 && first.getMessage == second.getMessage,
      "attempts: " + ThrowingInitializer.attempts.toString
    )
  }
