package org.fukuii.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import org.fukuii.bytes.Hex
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification for the EVM's 256-bit Keccak digest.
  *
  * WHAT IS BEING CERTIFIED, AND WHAT IS NOT. BouncyCastle implements Keccak and
  * ships its own tests for it; re-testing someone else's primitive is not this
  * suite's job. What is ours, and what these vectors check, is that we selected
  * the right function and wired it correctly. SHA-3 accepts the same inputs and
  * returns 32 equally plausible bytes, so choosing it by mistake is otherwise
  * invisible.
  *
  * WHICH PROPERTY DOES THAT, MEASURED RATHER THAN ASSUMED. Swapping this
  * module's `KeccakDigest` for `SHA3Digest` — the realistic wrong choice, one
  * import apart — fails the FIRST property and no other. So the published
  * vectors are what catch a wrong selection.
  *
  * The SHA3-disagrees property does NOT catch it, and passed against that
  * mutant. Its job is the other one: it guards the PREMISE the first property
  * rests on, that these vectors are Keccak's and not SHA-3's. If the table were
  * ever regenerated from the wrong function, the first property would pass
  * against a wrong implementation and this is the one that would fire. Keeping
  * both is deliberate; reading either as the other's substitute is not.
  *
  * Every vector is another implementation's, and no two come from the same one
  * where a choice existed:
  *
  *   - the empty input, as `Hash.EMPTY` in besu
  *     `datatypes/src/test/java/org/hyperledger/besu/datatypes/HashTest.java`
  *     @ `besu-eth/besu, main, fd8389c5 (2026-07-31)`, and independently as
  *     `KeccakOfAnEmptyString` in nethermind
  *     `src/Nethermind/Nethermind.Core.Test/KeccakTests.cs`
  *     @ `NethermindEth/nethermind, master, 2706ce9e (2026-07-31)`
  *   - `"abc"`, from go-ethereum `crypto/keccak/sha3_test.go`'s `TestKeccak`
  *     @ `ethereum/go-ethereum, master, 7a1b1156 (2026-07-30)`
  *   - `"cow"` and `"horse"`, from besu
  *     `crypto/algorithms/src/test/java/org/hyperledger/besu/crypto/HashTest.java`
  *     @ `besu-eth/besu, main, fd8389c5 (2026-07-31)`
  */
class Keccak256PropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def ascii(s: String): IArray[Byte] = IArray.unsafeFromArray(s.getBytes(UTF_8))

  private val vectors = Table(
    ("input", "expectedHex"),
    ("", "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"),
    ("abc", "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45"),
    ("cow", "c85ef7d79691fe79573b1a7064c19c1a9819ebdbd1faaab1a8ec92344438aaf4"),
    ("horse", "c87f65ff3f271bf5dc8643484f66b200109caffe4bf98c4cb393dc35740b28c0")
  )

  property("keccak256 reproduces the digest the reference clients publish") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      assert(Keccak256.hash(ascii(input)).toHex == expectedHex, "keccak256 of " + input)
    }
  }

  property("keccak256 returns 32 bytes for every input") {
    forAll(vectors) { (input: String, _: String) =>
      assert(Keccak256.hash(ascii(input)).toBytes.length == 32, "digest width for " + input)
    }
  }

  /** Guards the premise, not the selection — see the note on this class.
    *
    * It fires if the table above is ever regenerated from SHA-3, which is the
    * one way the vector property could pass against a wrong implementation.
    */
  property("the JDK's SHA3-256 disagrees with every one of them") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      val sha3 = MessageDigest.getInstance("SHA3-256").digest(input.getBytes(UTF_8))
      assert(
        Hex.encode(IArray.unsafeFromArray(sha3)) != expectedHex,
        "SHA3-256 must NOT match the Keccak vector for " + input + "; if it does, the two are indistinguishable here"
      )
    }
  }

  property("a one-bit change in the input changes the digest") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      assert(Keccak256.hash(ascii(input + " ")).toHex != expectedHex, "appending a byte must change " + input)
    }
  }
