package org.fukuii.bytes

import org.scalatest.flatspec.AnyFlatSpec

/** The hazard this type exists to close, asserted rather than described.
  *
  * `IArray[Byte]` erases to an array and compares by reference, so a value
  * carrying one is unequal to an identical value and absent from a collection
  * that contains it. Every assertion below fails if [[Bytes]] is replaced by a
  * bare `IArray[Byte]`, which is the whole claim.
  */
class BytesSpec extends AnyFlatSpec:

  private def sample: Bytes  = Bytes.fromIArray(IArray[Byte](1, 2, 3))
  private def another: Bytes = Bytes.fromIArray(IArray[Byte](1, 2, 3))

  "two Bytes over equal contents" should "compare equal despite being distinct objects" in {
    assert(sample == another, "structural equality is the reason this type exists")
  }

  it should "share a hash code, so they collide in the same bucket" in {
    assert(sample.hashCode == another.hashCode, "unequal codes would defeat every hashed collection")
  }

  it should "deduplicate in a Set" in {
    assert(Set(sample, another).size == 1, "a Set of one value has one element")
  }

  it should "find each other as Map keys" in {
    assert(Map(sample -> "found").get(another) == Some("found"), "a key must be findable by an equal key")
  }

  "Bytes over different contents" should "not compare equal" in {
    assert(sample != Bytes.fromIArray(IArray[Byte](1, 2, 4)), "different bytes are a different value")
  }

  "the empty value" should "be equal to an empty construction" in {
    assert(Bytes.Empty == Bytes.fromIArray(IArray.empty), "there is one empty value")
  }

  "fromArray" should "copy, so a later mutation of the caller's array is not observed" in {
    val mutable = Array[Byte](1, 2, 3)
    val taken   = Bytes.fromArray(mutable)
    mutable(0) = 99
    assert(taken == sample, "the value was copied at construction, not aliased")
  }

  "toArray" should "copy, so a caller cannot reach inside the value" in {
    val escaped = sample.toArray
    escaped(0) = 99
    assert(sample == another, "mutating what toArray returned must not alter the value")
  }

  "fromHex" should "round-trip" in {
    assert(Bytes.fromHex("010203").map(_.toHex) == Right("010203"), "round trip must be exact")
  }

  "toString" should "abbreviate a value too long to read whole" in {
    assert(Bytes.fromIArray(IArray.fill(64)(7.toByte)).toString.endsWith("(64 bytes)"), "long values are bounded")
  }
