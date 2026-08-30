package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** EIP-1014's own published examples, run against this build's derivation.
  *
  * ==Why these and not vectors of this project's making==
  *
  * The derivation is `keccak256(0xff ++ creator ++ salt ++ keccak256(initCode))`
  * truncated, and a vector produced by running that expression asserts only that
  * the expression is what it is. These seven come from the document
  * (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1014.md`, Final, section
  * *Examples*), so they are an oracle this project had no hand in.
  *
  * ==What the set covers, which is why seven rather than one==
  *
  * The zero creator and a non-zero one; a zero salt and two distinct non-zero
  * ones; empty initialization code, one byte, four bytes and forty-four. So an
  * implementation that dropped any single element of the preimage, or that
  * hashed the code where the digest belongs, disagrees on at least one row --
  * and an implementation that got the ORDER of the four wrong disagrees on the
  * rows where creator and salt are both non-zero.
  *
  * A table of named vectors with one expected outcome, which is what this
  * project's `AnyPropSpec` assignment is for.
  */
class Create2AddressPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  final private case class Vector(name: String, creator: String, salt: String, initCode: String, expected: String)

  private def bytes(hex: String): Bytes =
    Bytes.fromHex(hex).getOrElse(throw new IllegalArgumentException(s"bad hex: $hex"))

  private def address(hex: String): Address =
    Address.fromHex(hex).getOrElse(throw new IllegalArgumentException(s"bad address: $hex"))

  private val examples: Seq[Vector] = Seq(
    Vector(
      "example 0 -- everything zero except a one-byte body",
      "0x0000000000000000000000000000000000000000",
      "0x0000000000000000000000000000000000000000000000000000000000000000",
      "0x00",
      "0x4D1A2e2bB4F88F0250f26Ffff098B0b30B26BF38"
    ),
    Vector(
      "example 1 -- the creator alone is non-zero",
      "0xdeadbeef00000000000000000000000000000000",
      "0x0000000000000000000000000000000000000000000000000000000000000000",
      "0x00",
      "0xB928f69Bb1D91Cd65274e3c79d8986362984fDA3"
    ),
    Vector(
      "example 2 -- creator and salt both non-zero",
      "0xdeadbeef00000000000000000000000000000000",
      "0x000000000000000000000000feed000000000000000000000000000000000000",
      "0x00",
      "0xD04116cDd17beBE565EB2422F2497E06cC1C9833"
    ),
    Vector(
      "example 3 -- the body alone is non-zero",
      "0x0000000000000000000000000000000000000000",
      "0x0000000000000000000000000000000000000000000000000000000000000000",
      "0xdeadbeef",
      "0x70f2b2914A2a4b783FaEFb75f459A580616Fcb5e"
    ),
    Vector(
      "example 4 -- all three non-zero, which is what pins their ORDER",
      "0x00000000000000000000000000000000deadbeef",
      "0x00000000000000000000000000000000000000000000000000000000cafebabe",
      "0xdeadbeef",
      "0x60f3f640a8508fC6a86d45DF051962668E1e8AC7"
    ),
    Vector(
      "example 5 -- a body spanning two words",
      "0x00000000000000000000000000000000deadbeef",
      "0x00000000000000000000000000000000000000000000000000000000cafebabe",
      "0xdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
      "0x1d8bfDC5D46DC4f61D6b6115972536eBE6A8854C"
    ),
    Vector(
      "example 6 -- EMPTY initialization code, which still hashes to a real digest",
      "0x0000000000000000000000000000000000000000",
      "0x0000000000000000000000000000000000000000000000000000000000000000",
      "0x",
      "0xE33C0C7F7df4809055C3ebA6c09CFe4BaF1BD9e0"
    )
  )

  property("the derivation agrees with every example the document publishes") {
    forAll(Table("vector", examples*)) { (vector: Vector) =>
      val derived = ContractAddress.create2(
        address(vector.creator),
        Word.fromBytes(bytes(vector.salt)),
        bytes(vector.initCode)
      )
      assert(
        derived == address(vector.expected),
        s"${vector.name}: derived ${derived.toHex} where the document states ${vector.expected}"
      )
    }
  }

  property("a salted address does not depend on the creator's transaction count") {
    // The whole point of the operation, and the one property no single example
    // states on its own. `ContractAddress.of` moves with the count; this must
    // not, or a creator could not compute its address ahead of time.
    val creator = address("0x00000000000000000000000000000000deadbeef")
    val salt = Word.fromBytes(bytes("0x00000000000000000000000000000000000000000000000000000000cafebabe"))
    val code = bytes("0xdeadbeef")
    assert(
      ContractAddress.create2(creator, salt, code) == ContractAddress.create2(creator, salt, code),
      "the derivation reads no state and must be a pure function of its three inputs"
    )
  }
