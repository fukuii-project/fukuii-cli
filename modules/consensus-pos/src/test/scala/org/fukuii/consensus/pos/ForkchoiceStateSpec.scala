package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.Hash

/** ==What this suite deliberately does not assert==
  *
  * The safe-is-ancestor-of-head requirement. Ancestry is a question about a
  * chain and this value is three hashes, so a test here could only assert a
  * check this type does not perform — which would be a test of the fixture
  * rather than of the type. The requirement is stated on the type and is the
  * verb's to enforce once a chain exists to enforce it against.
  */
class ForkchoiceStateSpec extends AnyFlatSpec:

  private val zero: Hash = Hash.fromBytesTruncating(IArray.empty)
  private val head: Hash = PosFixtures.hash(0x01)
  private val safe: Hash = PosFixtures.hash(0x02)
  private val finalized: Hash = PosFixtures.hash(0x03)

  "ForkchoiceState" should "distinguish two states differing only in the safe hash" in
    assert(
      ForkchoiceState(head, safe, finalized) != ForkchoiceState(head, finalized, finalized),
      "the three fields are three distinct facts and none is derivable from the others"
    )

  it should "admit a zero hash where nothing is finalized yet" in
    assert(
      ForkchoiceState(head, zero, zero).finalizedBlockHash == zero,
      "the specification's way of saying none yet is a hash of zeros, so it must be an ordinary value"
    )

  it should "be equal by value" in
    assert(
      ForkchoiceState(head, safe, finalized) == ForkchoiceState(head, safe, finalized),
      "two states naming the same three blocks are the same state"
    )

  it should "find a value-equal state used as a map key" in {
    val key = ForkchoiceState(head, safe, finalized)
    assert(
      Map(key -> 1).get(ForkchoiceState(head, safe, finalized)).contains(1),
      "the hashes compare by value, so a state built twice must hash the same"
    )
  }
