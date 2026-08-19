package org.fukuii.evm

import org.fukuii.bytes.Bytes

/** A program the machine can run, together with the jump destinations it
  * admits.
  *
  * ==Where a jump may land is a property of the code, not of the jump==
  *
  * A `JUMPDEST` byte only marks a destination when it is an instruction. The
  * same byte sitting inside a `PUSH`'s operand is data, and jumping into it is
  * how a program would otherwise reach an instruction boundary the author never
  * wrote. Deciding that per jump would mean rescanning from the start of the
  * code every time, so it is decided once here, when the code arrives.
  *
  * ==The scan reads the vocabulary, never the table==
  *
  * Which bytes carry an operand is [[Opcode]]'s answer rather than
  * [[OpcodeTable]]'s, so a chain that removes an operation does not thereby
  * move its own jump destinations. The executable specification takes the same
  * reading -- its scan skips a byte it does not recognize instead of stopping --
  * and go-ethereum's scan likewise tests a fixed push range rather than
  * consulting the active table.
  */
final class Code(val bytes: Bytes):

  private val raw: IArray[Byte] = bytes.toIArray

  def length: Int = raw.length

  /** The byte at `position`, read as a number in `[0, 256)`. */
  def byteAt(position: Int): Int = raw(position) & 0xff

  /** `size` bytes from `position`, zero-filled where the code runs out.
    *
    * A `PUSH` near the end of the code reads past it, and the specification
    * pads rather than failing: the operand is short, not missing.
    */
  def read(position: Int, size: Int): Bytes =
    val out = new Array[Byte](size)
    var i = 0
    while i < size do
      val source = position + i
      if source >= 0 && source < raw.length then out(i) = raw(source)
      i += 1
    Bytes.fromIArray(IArray.unsafeFromArray(out))

  /** Every position a `JUMP` or `JUMPI` may land on. */
  val validJumpDestinations: Set[Int] =
    val found = Set.newBuilder[Int]
    var position = 0
    while position < raw.length do
      val opcode = Opcode.fromCode(raw(position) & 0xff)
      if opcode.contains(Opcode.JumpDest) then
        val _ = found += position
      position += 1 + opcode.map(Opcode.immediateWidth).getOrElse(0)
    found.result()

object Code:

  val Empty: Code = new Code(Bytes.Empty)

  def apply(bytes: Bytes): Code = new Code(bytes)
