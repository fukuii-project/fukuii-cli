package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The loop, the charging, and the ends execution can reach.
  *
  * Expected values are `forks/frontier/vm/` at `ccaaaba58`: the prices from
  * `gas.py`, the order of operand, charge and program counter from each
  * operation in `instructions/`, and the two ends from `interpreter.py`'s loop
  * condition and its handling of an exceptional halt.
  */
class InterpreterSpec extends AnyFlatSpec:

  private val schedule = GasSchedule.Baseline
  private val table = OpcodeTable.baseline(schedule)

  private def frameOf(gas: Int, program: Int*): Frame =
    new Frame(Code(Bytes.fromArray(program.map(_.toByte).toArray)), BigInt(gas))

  private def exec(gas: Int, program: Int*): (Frame, Either[Unsupported, Outcome]) =
    val frame = frameOf(gas, program*)
    (frame, Interpreter.run(frame, table, schedule))

  private def w(value: Int): Word = Word(BigInt(value))

  // PUSH1 0x03, PUSH1 0x05, then the operation under test.
  private def afterBinary(gas: Int, operation: Int): (Frame, Either[Unsupported, Outcome]) =
    exec(gas, 0x60, 0x03, 0x60, 0x05, operation)

  // The operations this build does not yet run, named rather than counted, so
  // that implementing one fails this test until it is taken off the list and
  // adding an operation to the table without an implementation fails it too.
  private val notYetBuilt: Set[Opcode] = Set(
    Opcode.Keccak256,
    Opcode.Address,
    Opcode.Balance,
    Opcode.Origin,
    Opcode.Caller,
    Opcode.CallValue,
    Opcode.CallDataLoad,
    Opcode.CallDataSize,
    Opcode.CallDataCopy,
    Opcode.CodeSize,
    Opcode.CodeCopy,
    Opcode.GasPrice,
    Opcode.ExtCodeSize,
    Opcode.ExtCodeCopy,
    Opcode.BlockHash,
    Opcode.Coinbase,
    Opcode.Timestamp,
    Opcode.Number,
    Opcode.Difficulty,
    Opcode.GasLimit,
    Opcode.SLoad,
    Opcode.SStore,
    Opcode.Log0,
    Opcode.Log1,
    Opcode.Log2,
    Opcode.Log3,
    Opcode.Log4,
    Opcode.Create,
    Opcode.Call,
    Opcode.CallCode,
    Opcode.Return,
    Opcode.SelfDestruct
  )

  private def cannotRun(opcode: Opcode): Boolean =
    val (_, outcome) = exec(1000000, opcode.code)
    outcome.isLeft

  "running off the end of the code" should "end normally rather than exceptionally" in {
    val (_, outcome) = exec(100, 0x60, 0x01)
    assert(
      outcome == Right(Outcome.Stopped(BigInt(97), Bytes.Empty)),
      "the loop ends on the program counter, with no terminator required"
    )
  }

  "an empty program" should "stop immediately having spent nothing" in {
    val (_, outcome) = exec(100)
    assert(
      outcome == Right(Outcome.Stopped(BigInt(100), Bytes.Empty)),
      "there is nothing to run and nothing to charge for"
    )
  }

  "STOP" should "end execution before the rest of the code runs" in {
    val (frame, _) = exec(100, 0x00, 0x60, 0x01)
    assert(frame.stack.isEmpty, "the PUSH after STOP never ran")
  }

  it should "cost nothing" in {
    val (frame, _) = exec(100, 0x00)
    assert(frame.gasLeft == BigInt(100), "STOP makes no charge at all in the specification")
  }

  "a byte naming no operation" should "halt and report which byte it was" in {
    val (_, outcome) = exec(100, 0x0c)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidOpcode(0x0c))),
      "a chain split is diagnosed from the byte, so it is carried"
    )
  }

  "an exceptional halt" should "leave no gas on the frame" in {
    val (frame, _) = exec(100, 0x0c)
    assert(frame.gasLeft == BigInt(0), "at this fork an exceptional halt consumes everything remaining")
  }

  "ADD" should "leave the sum on the stack" in {
    val (frame, _) = afterBinary(100, 0x01)
    assert(frame.stack.peek(0) == Right(w(8)), "three plus five")
  }

  it should "cost the very low tier" in {
    val (frame, _) = afterBinary(100, 0x01)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 3), "two pushes at 3 and an ADD at 3")
  }

  "SUB" should "subtract the second operand from the top one" in {
    val (frame, _) = afterBinary(100, 0x03)
    assert(frame.stack.peek(0) == Right(w(2)), "the top is the left operand, so this is five minus three")
  }

  "MUL" should "cost the low tier rather than the very low one" in {
    val (frame, _) = afterBinary(100, 0x02)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 5), "MUL is priced above ADD")
  }

  "LT" should "compare the top against the one below it" in {
    val (frame, _) = afterBinary(100, 0x10)
    assert(frame.stack.peek(0) == Right(Word.Zero), "five is not less than three")
  }

  "BYTE" should "take the index from the top and the word from below it" in {
    val (frame, _) = exec(100, 0x60, 0xff, 0x60, 0x1f, 0x1a)
    assert(frame.stack.peek(0) == Right(w(0xff)), "index 31 is the least significant byte")
  }

  "SIGNEXTEND" should "take the width from the top and the value from below it" in {
    val (frame, _) = exec(100, 0x60, 0xff, 0x60, 0x00, 0x0b)
    assert(frame.stack.peek(0) == Right(Word.MaxValue), "0xff extended from byte zero is negative one")
  }

  "EXP" should "charge for each byte of the exponent" in {
    val (frame, _) = exec(100, 0x61, 0x01, 0x00, 0x60, 0x02, 0x0a)
    assert(
      frame.gasLeft == BigInt(100 - 3 - 3 - (10 + 10 * 2)),
      "the exponent is the operand pushed first, and two bytes of it cost the base plus twice the per-byte rate"
    )
  }

  it should "charge only the base when the exponent is zero" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x02, 0x0a)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 10), "a zero exponent occupies no bytes")
  }

  "an operation that cannot be paid for" should "halt out of gas" in {
    val (_, outcome) = exec(5, 0x60, 0x01, 0x60, 0x01, 0x01)
    assert(outcome == Right(Outcome.Halted(Halt.OutOfGas)), "two pushes cost six and only five were provided")
  }

  "an operation with nothing to take" should "halt under the stack" in {
    val (_, outcome) = exec(100, 0x01)
    assert(outcome == Right(Outcome.Halted(Halt.StackUnderflow)), "ADD takes two operands from an empty stack")
  }

  "PUSH1" should "step over its operand rather than running it" in {
    val (frame, _) = exec(100, 0x60, 0x00)
    assert(frame.stack.peek(0) == Right(Word.Zero) && frame.pc == 2, "the operand byte is data, not a STOP")
  }

  "PUSH32" should "zero-fill an operand the code runs out of" in {
    val (frame, _) = exec(100, 0x7f, 0x01)
    assert(frame.stack.peek(0) == Right(Word(BigInt(1) << 248)), "one byte read and thirty-one zeros after it")
  }

  "DUP1" should "copy the top of the stack" in {
    val (frame, _) = exec(100, 0x60, 0x07, 0x80)
    assert(frame.stack.depth == 2 && frame.stack.peek(1) == Right(w(7)), "the copy is above the original")
  }

  "DUP2" should "copy the element below the top" in {
    val (frame, _) = exec(100, 0x60, 0x01, 0x60, 0x02, 0x81)
    assert(frame.stack.peek(0) == Right(w(1)), "DUP2 names the second element, counting the top as the first")
  }

  "DUP1 against an empty stack" should "halt under the stack" in {
    val (_, outcome) = exec(100, 0x80)
    assert(outcome == Right(Outcome.Halted(Halt.StackUnderflow)), "there is nothing to duplicate")
  }

  "SWAP1" should "exchange the top with the element below it" in {
    val (frame, _) = exec(100, 0x60, 0x01, 0x60, 0x02, 0x90)
    assert(
      frame.stack.peek(0) == Right(w(1)) && frame.stack.peek(1) == Right(w(2)),
      "the two are exchanged, not rotated"
    )
  }

  "PC" should "report the position of the instruction that ran" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x58)
    assert(frame.stack.peek(0) == Right(w(2)), "the counter is read before it moves on")
  }

  "GAS" should "report what remains once the operation has been paid for" in {
    val (frame, _) = exec(100, 0x5a)
    assert(frame.stack.peek(0) == Right(w(98)), "the charge of two is made before the value is read")
  }

  "JUMP" should "continue at a marked destination" in {
    val (frame, _) = exec(100, 0x60, 0x03, 0x56, 0x5b)
    assert(frame.pc == 4, "the jump landed on the marker at position three and then ran it")
  }

  it should "halt when the destination carries no marker" in {
    val (_, outcome) = exec(100, 0x60, 0x00, 0x56)
    assert(outcome == Right(Outcome.Halted(Halt.InvalidJumpDestination)), "position zero holds a PUSH, not a JUMPDEST")
  }

  it should "halt when the destination is beyond anything addressable" in {
    val (_, outcome) = exec(100, (0x7f +: Seq.fill(32)(0xff)) :+ 0x56*)
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidJumpDestination)),
      "a destination no code position can equal is refused as a destination rather than overflowing an index"
    )
  }

  "JUMPI" should "fall through when the condition is zero" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x06, 0x57, 0x00, 0x5b)
    assert(frame.pc == 6, "the counter advanced by one rather than jumping to six")
  }

  it should "jump when the condition is not zero" in {
    val (frame, _) = exec(100, 0x60, 0x01, 0x60, 0x06, 0x57, 0x00, 0x5b)
    assert(frame.pc == 7, "the marker at six was reached and then run")
  }

  "MSTORE then MLOAD" should "return the word that was written" in {
    val (frame, _) = exec(100, 0x60, 0x2a, 0x60, 0x00, 0x52, 0x60, 0x00, 0x51)
    assert(frame.stack.peek(0) == Right(w(0x2a)), "memory round-trips a word at the offset it was written to")
  }

  "MSTORE" should "charge for the memory it makes the machine hold" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0x52)
    assert(frame.gasLeft == BigInt(100 - 3 - 3 - 3 - 3), "the base of three plus one word of memory at three")
  }

  "MSIZE" should "report the memory rounded up to a whole word" in {
    val (frame, _) = exec(100, 0x60, 0x00, 0x60, 0x00, 0x53, 0x59)
    assert(frame.stack.peek(0) == Right(w(32)), "writing one byte at offset zero makes the machine hold a whole word")
  }

  "MSTORE8" should "write only the low byte of its operand" in {
    val (frame, _) = exec(200, 0x61, 0x01, 0xff, 0x60, 0x00, 0x53, 0x60, 0x00, 0x51)
    assert(frame.stack.peek(0) == Right(Word(BigInt(0xff) << 248)), "0x01ff stores as 0xff at the first byte of memory")
  }

  "an offset no memory can reach" should "halt out of gas rather than be allocated" in {
    val (_, outcome) = exec(1000000, (0x7f +: Seq.fill(32)(0xff)) :+ 0x51*)
    assert(
      outcome == Right(Outcome.Halted(Halt.OutOfGas)),
      "holding that much memory costs more than any frame can carry, so it is refused as unaffordable"
    )
  }

  "the operations this build cannot run" should "be exactly the ones reaching a later phase" in
    assert(
      Opcode.values.filter(cannotRun).toSet == notYetBuilt,
      "an operation that quietly stopped running would otherwise look like one that was never built"
    )

  "an operation this build cannot run" should "not be reported as a halt" in {
    val (_, outcome) = exec(1000000, Opcode.SLoad.code)
    assert(outcome == Left(Unsupported(Opcode.SLoad)), "a halt is a result a chain reaches, and this is not one")
  }

  "a table that has had an operation removed" should "treat its byte as naming none" in {
    val frame = frameOf(100, 0xff)
    assert(
      Interpreter.run(frame, table.removing(Opcode.SelfDestruct), schedule) ==
        Right(Outcome.Halted(Halt.InvalidOpcode(0xff))),
      "a removed operation behaves exactly as an undefined byte, which is what scroll-tech/go-ethereum records at its own removal"
    )
  }
