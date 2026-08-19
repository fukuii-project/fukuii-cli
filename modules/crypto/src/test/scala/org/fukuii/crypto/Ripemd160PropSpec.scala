package org.fukuii.crypto

import java.nio.charset.StandardCharsets.UTF_8
import org.fukuii.bytes.Hex
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Certification for RIPEMD-160.
  *
  * WHAT IS BEING CERTIFIED, AND WHAT IS NOT. BouncyCastle implements the family
  * and ships its own tests; what is ours is that we selected the 160-bit member
  * rather than one of the other three widths, and wired it correctly.
  *
  * WHERE THE VECTORS COME FROM, AND WHY THEY ARE TWO SOURCES. Each is published
  * by nethermind as a known vector in
  * `src/Nethermind/Nethermind.Core.Test/RipemdTests.cs`
  * @ `NethermindEth/nethermind, master, c35ce1b1ab` — carried there in the
  * precompile's own padded form, which is why they are recorded here unpadded
  * and padded separately. Each was then recomputed on this machine by
  * `openssl dgst -ripemd160`, an implementation independent of both, and all
  * four agreed.
  *
  * THE JDK CANNOT BE A THIRD. It ships no RIPEMD-160, which is why the sibling
  * [[Sha256PropSpec]] has an in-process cross-check and this does not.
  */
class Ripemd160PropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def ascii(s: String): IArray[Byte] = IArray.unsafeFromArray(s.getBytes(UTF_8))

  private val vectors = Table(
    ("input", "expectedHex"),
    ("", "9c1185a5c5e9fc54612808977ee8f548b2258d31"),
    ("abc", "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc"),
    ("message digest", "5d0689ef49d2fae572b881b123a85ffa21595f36"),
    ("abcdefghijklmnopqrstuvwxyz", "f71c27109c692c1b56bbdceb5b9d2865b3708dbc")
  )

  property("ripemd160 reproduces the published digest") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      assert(Hex.encode(Ripemd160.hash(ascii(input))) == expectedHex, "ripemd160 of " + input)
    }
  }

  property("ripemd160 returns 20 bytes for every input") {
    forAll(vectors) { (input: String, _: String) =>
      assert(Ripemd160.hash(ascii(input)).length == Ripemd160.Width, "digest width for " + input)
    }
  }

  property("a one-byte change in the input changes the digest") {
    forAll(vectors) { (input: String, expectedHex: String) =>
      assert(Hex.encode(Ripemd160.hash(ascii(input + " "))) != expectedHex, "appending a byte must change " + input)
    }
  }
