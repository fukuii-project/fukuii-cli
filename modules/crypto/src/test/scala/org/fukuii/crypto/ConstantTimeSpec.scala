package org.fukuii.crypto

import org.scalatest.flatspec.AnyFlatSpec

/** These assert the RESULT, not the timing.
  *
  * A wall-clock assertion on a constant-time property is the wrong instrument:
  * it is flaky under load, and it goes inert on faster hardware without
  * announcing that it has. What is checked here is that the comparison answers
  * correctly in the cases an early-exit loop gets wrong — an early difference,
  * a late one, and unequal lengths.
  *
  * The timing property itself is established by reading the implementation: it
  * accumulates over the whole length and branches on nothing the content
  * decides. That is not something a test on this side of the JIT can assert.
  */
class ConstantTimeSpec extends AnyFlatSpec:

  private def bytes(values: Int*): IArray[Byte] = IArray.from(values.map(_.toByte))

  "ConstantTime.equal" should "accept two equal sequences" in
    assert(ConstantTime.equal(bytes(1, 2, 3, 4), bytes(1, 2, 3, 4)), "equal contents must compare equal")

  it should "accept two empty sequences" in
    assert(ConstantTime.equal(IArray.empty[Byte], IArray.empty[Byte]), "nothing differs between two empty sequences")

  it should "compare by value rather than by identity" in {
    val once = bytes(9, 9, 9)
    assert(ConstantTime.equal(once, bytes(9, 9, 9)), "a separately built equal value must compare equal")
  }

  it should "reject a difference in the first byte" in
    assert(!ConstantTime.equal(bytes(0, 2, 3, 4), bytes(1, 2, 3, 4)), "an early difference must be found")

  it should "reject a difference in the last byte" in
    assert(!ConstantTime.equal(bytes(1, 2, 3, 4), bytes(1, 2, 3, 5)), "a late difference must be found")

  it should "reject a shorter sequence" in
    assert(!ConstantTime.equal(bytes(1, 2, 3), bytes(1, 2, 3, 4)), "a prefix is not equal to the whole")

  it should "reject a longer sequence" in
    assert(!ConstantTime.equal(bytes(1, 2, 3, 4), bytes(1, 2, 3)), "unequal lengths cannot be equal")

  it should "reject an empty sequence against a non-empty one" in
    assert(!ConstantTime.equal(IArray.empty[Byte], bytes(0)), "empty is not equal to a single zero byte")

  it should "not mutate its arguments" in {
    val left = bytes(1, 2, 3)
    val _ = ConstantTime.equal(left, bytes(9, 9, 9))
    assert(left(0) == 1.toByte, "the caller's value must survive the comparison")
  }
