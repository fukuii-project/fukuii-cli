package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** Which positions a jump may land on.
  *
  * The rules are `forks/frontier/vm/runtime.py`'s at `ccaaaba58`: a position
  * counts when it is inside the code, carries `JUMPDEST`, and is not part of a
  * `PUSH` operand. The cases below are the three ways that goes wrong.
  */
class CodeSpec extends AnyFlatSpec:

  private def code(bytes: Int*): Code =
    Code(Bytes.fromArray(bytes.map(_.toByte).toArray))

  "jump analysis" should "find a marker at the start of the code" in
    assert(Code(Bytes.fromArray(Array(0x5b.toByte))).validJumpDestinations == Set(0), "position zero is a position")

  it should "find nothing in code that has none" in
    assert(code(0x60, 0x01, 0x00).validJumpDestinations.isEmpty, "no JUMPDEST byte, no destination")

  it should "refuse a marker byte sitting inside a push operand" in
    assert(
      code(0x60, 0x5b, 0x00).validJumpDestinations.isEmpty,
      "the 0x5b at position 1 is PUSH1's operand, so it is data rather than an instruction"
    )

  it should "find the marker that follows a push operand" in
    assert(
      code(0x60, 0x01, 0x5b).validJumpDestinations == Set(2),
      "the scan resumes at the instruction after the operand, not one byte on"
    )

  it should "step over the whole operand of a wide push" in
    assert(
      code(0x7f, 0x5b, 0x5b, 0x5b, 0x5b, 0x5b, 0x5b, 0x5b, 0x5b).validJumpDestinations.isEmpty,
      "PUSH32 swallows the rest of this code, so none of the marker bytes is an instruction"
    )

  it should "keep scanning past a byte that names no operation" in
    assert(
      code(0x0c, 0x5b).validJumpDestinations == Set(1),
      "an unknown byte occupies one position and does not stop the scan, which the specification states outright"
    )

  it should "not run off the end when a push operand is truncated" in
    assert(code(0x61, 0x01).validJumpDestinations.isEmpty, "PUSH2 with one operand byte available ends the scan")

  "reading code" should "zero-fill a push operand the code runs out of" in
    assert(
      code(0x61, 0x01).read(1, 2) == Bytes.fromArray(Array(0x01.toByte, 0x00.toByte)),
      "a short operand is padded on the right, which is what makes the pushed value well defined"
    )
