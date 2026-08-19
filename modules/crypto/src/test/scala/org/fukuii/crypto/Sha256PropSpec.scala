package org.fukuii.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.fukuii.bytes.Hex
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification for SHA-256.
  *
  * WHAT IS BEING CERTIFIED, AND WHAT IS NOT. BouncyCastle implements SHA-2 and
  * ships its own tests for it; re-testing someone else's primitive is not this
  * suite's job. What is ours is that we selected the right function of the
  * several that return 32 bytes, and wired it correctly. SHA3-256 and
  * SHA-512/256 accept the same inputs and return 32 equally plausible bytes.
  *
  * WHERE THE VECTORS COME FROM. Each digest was computed on this machine by
  * `sha256sum` (GNU coreutils), an implementation independent of the provider
  * under test. `"abc"` is additionally FIPS 180-4's own worked example.
  *
  * WHAT THE JDK PROPERTY ADDS. A third implementation, in process, agreeing on
  * every vector — so a wrong selection has to be wrong in the same way in two
  * unrelated providers to survive. It does not replace the vectors: two
  * providers asked for the same wrong function would agree with each other, and
  * only a published value catches that.
  */
class Sha256PropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def ascii(s: String): IArray[Byte] = IArray.unsafeFromArray(s.getBytes(UTF_8))

  private val vectors = Table(
    ("input", "expectedHex"),
    ("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
    ("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
    ("message digest", "f7846f55cf23e14eebeab5b4e1550cad5b509e3348fbc4efa3a1413d393cb650"),
    ("abcdefghijklmnopqrstuvwxyz", "71c480df93d6ae2f1efad1447c66c9525e316218cf51fc8d9ed832f2daf18b73")
  )

  property("sha256 reproduces the published digest") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      assert(Sha256.hash(ascii(input)).toHex == expectedHex, "sha256 of " + input)
    }
  }

  property("sha256 returns 32 bytes for every input") {
    forAll(vectors) { (input: String, _: String) =>
      assert(Sha256.hash(ascii(input)).toBytes.length == 32, "digest width for " + input)
    }
  }

  property("the JDK's own SHA-256 agrees with every one of them") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      val jdk = MessageDigest.getInstance("SHA-256").digest(input.getBytes(UTF_8))
      assert(Hex.encode(IArray.unsafeFromArray(jdk)) == expectedHex, "a second provider must agree for " + input)
    }
  }

  /** Guards the premise the vectors rest on, not the selection.
    *
    * It fires if the table above is ever regenerated from SHA3-256, which is
    * the one way the vector property could pass against a wrong implementation.
    */
  property("the JDK's SHA3-256 disagrees with every one of them") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      val sha3 = MessageDigest.getInstance("SHA3-256").digest(input.getBytes(UTF_8))
      assert(
        Hex.encode(IArray.unsafeFromArray(sha3)) != expectedHex,
        "SHA3-256 must NOT match the SHA-256 vector for " + input + "; if it does, the two are indistinguishable here"
      )
    }
  }
