package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** What counts as a delegation designation, and what must not.
  *
  * ==The negative cases are the point==
  *
  * A designation is read out of ordinary code, so every rule that loads code
  * meets one. The cost of reading a designation too eagerly is that an account
  * holding deployed code beginning with the marker bytes would have twenty
  * bytes of that code followed as an address -- so the width test and the
  * prefix test both have to hold, and each is asserted alone.
  */
class DelegationSpec extends AnyFlatSpec:

  private val target: Address =
    Address.fromBytes(IArray.fill(Address.Width)(0x42.toByte)).getOrElse(throw new AssertionError("fixture"))

  "a designation" should "be the marker followed by the address it names" in
    assert(
      Delegation.designating(target).toIArray.toList ==
        (List(0xef, 0x01, 0x00).map(_.toByte) ++ target.toBytes.toList),
      "three marker bytes and then twenty of address"
    )

  it should "be twenty-three bytes wide" in
    assert(
      Delegation.designating(target).toIArray.length == 23 && Delegation.Width == 23,
      "the width the specification requires exactly, rather than a minimum"
    )

  it should "read back the address it names" in
    assert(
      Delegation.targetOf(Delegation.designating(target)).contains(target),
      "what is written is what is read"
    )

  "code one byte too long" should "not be a designation" in
    // The case a prefix test passes and a width test refuses. Without the width
    // check, this reads an address out of the first twenty bytes after the
    // marker and follows it.
    assert(
      Delegation.targetOf(Bytes.fromIArray(Delegation.designating(target).toIArray :+ 0x00.toByte)).isEmpty,
      "a designation is exactly twenty-three bytes, so longer code is code"
    )

  "code one byte too short" should "not be a designation" in
    assert(
      Delegation.targetOf(Bytes.fromIArray(Delegation.designating(target).toIArray.dropRight(1))).isEmpty,
      "shorter than the width, so there is no address to read"
    )

  "code of the right width with a different prefix" should "not be a designation" in
    // The case a width test passes and a prefix test refuses.
    assert(
      Delegation.targetOf(Bytes.fromIArray(IArray.fill(Delegation.Width)(0xef.toByte))).isEmpty,
      "the first byte alone is not the marker"
    )

  it should "not be a designation when only the last marker byte differs" in
    // The narrowest prefix case there is: a build comparing fewer than three
    // bytes admits this one.
    assert(
      Delegation
        .targetOf(Bytes.fromIArray(IArray(0xef.toByte, 0x01.toByte, 0x01.toByte) ++ target.toBytes))
        .isEmpty,
      "all three marker bytes are compared, not a leading subset"
    )

  "empty code" should "not be a designation" in
    assert(
      Delegation.targetOf(Bytes.Empty).isEmpty,
      "the commonest account there is holds no code and must not read as delegated"
    )
