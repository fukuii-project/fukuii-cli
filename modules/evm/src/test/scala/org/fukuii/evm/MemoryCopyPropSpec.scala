package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** The four copies EIP-5656 publishes, run against the operation.
  *
  * Every row is transcribed from the document's own Test Cases section
  * (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-09), `EIPS/eip-5656.md`, Final):
  * the operands, the memory before, and the memory after. Nothing here is
  * derived -- a row that disagreed with the document would be a transcription
  * error rather than a different reading of it.
  *
  * ==Two of the four overlap, in opposite directions, and that is why the
  * document publishes four==
  *
  * `MCOPY 0 1 8` moves bytes down and `MCOPY 1 0 8` moves them up. An
  * implementation copying in place forward agrees with the first and corrupts
  * the second; one copying backward does the reverse. Only a buffered copy
  * answers both, which is what *"copying takes place as if an intermediate
  * buffer was used"* requires. **The two non-overlapping rows are the control**:
  * an implementation that failed either of them is broken in a way that has
  * nothing to do with overlap.
  *
  * ==The document's gas figure is checked in the sibling and not here==
  *
  * Each row states *"gas used: 6"*, which is `3 + 3 * 1` at Ethereum mainnet's
  * `veryLow` and per-word figures. [[EvmFixtures.schedule]] deliberately holds
  * neither, so this spec would have to assert a number the document does not
  * state. [[MemoryCopySpec]] runs the operation under the document's own two
  * figures and asserts the 6; the rows here assert what the copy PRODUCES.
  */
class MemoryCopyPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val schedule = EvmFixtures.schedule

  private val environment: Environment =
    EvmFixtures.environment(withTable = OpcodeTable.original(schedule).adding(Operation(Opcode.MCopy, Cost.Computed)))

  /** Runs a single `MCOPY` over `before`, with the operands placed directly.
    *
    * The operands are pushed rather than compiled into a program so that what
    * the frame spends is the operation's own charge and nothing else. Memory is
    * seeded the same way, because the document's cases all begin from memory
    * that is already allocated -- compiling the stores that would allocate it
    * would put their expansion charge inside the figure under test.
    */
  private def copied(before: Bytes, destination: Int, source: Int, size: Int): Bytes =
    val frame = new Frame(
      EvmFixtures.message(transfersValue = false),
      Code(Bytes.fromArray(Array(Opcode.MCopy.code.toByte))),
      BigInt(100000)
    )
    frame.memory.write(0, before)
    val _ = frame.stack.push(EvmFixtures.word(size))
    val _ = frame.stack.push(EvmFixtures.word(source))
    val _ = frame.stack.push(EvmFixtures.word(destination))
    val _ = Interpreter.run(frame, environment)
    frame.memory.read(0, before.length)

  private val a = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
  private val zeros = "0000000000000000000000000000000000000000000000000000000000000000"
  private val ones = "0101010101010101010101010101010101010101010101010101010101010101"
  private val ascending = "0001020304050607" + "08" + "0000000000000000000000000000000000000000000000"
  private val shiftedDown = "0102030405060708" + "08" + "0000000000000000000000000000000000000000000000"
  private val shiftedUp = "0000010203040506" + "07" + "0000000000000000000000000000000000000000000000"

  private val published = Table(
    ("case", "destination", "source", "size", "before", "after"),
    ("MCOPY 0 32 32 -- a whole word from the second word to the first", 0, 32, 32, zeros + a, a + a),
    ("MCOPY 0 0 32 -- a whole word onto itself", 0, 0, 32, ones, ones),
    ("MCOPY 0 1 8 -- eight bytes down by one, overlapping", 0, 1, 8, ascending, shiftedDown),
    ("MCOPY 1 0 8 -- eight bytes up by one, overlapping", 1, 0, 8, ascending, shiftedUp)
  )

  property("every copy the document publishes lands where the document says it does") {
    forAll(published) { (name: String, destination: Int, source: Int, size: Int, before: String, after: String) =>
      assert(
        copied(EvmFixtures.bytesOf(before), destination, source, size) == EvmFixtures.bytesOf(after),
        name + ": the document states this memory afterwards and the operation produced something else"
      )
    }
  }
