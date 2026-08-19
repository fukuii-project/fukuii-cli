package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The machine's stack, memory and gas arithmetic.
  *
  * Expected values come from `forks/frontier/vm/` at `ccaaaba58` — the stack
  * limit from `stack.py`'s own `len(stack) == 1024` and the interpreter's
  * `STACK_DEPTH_LIMIT`, the memory cost from `gas.py`'s
  * `calculate_memory_gas_cost`, whose form is
  * `words * MEMORY_PER_WORD + words**2 // 512`.
  */
class MachineSpec extends AnyFlatSpec:

  private def w(v: Int): Word = Word(BigInt(v))

  private def fill(stack: Stack, count: Int): Unit =
    var i = 0
    while i < count do
      val _ = stack.push(w(i))
      i += 1

  "a stack" should "return what was last pushed" in {
    val stack = new Stack
    val _ = stack.push(w(7))
    assert(stack.pop() == Right(w(7)), "a stack is last in, first out")
  }

  it should "report underflow rather than throwing when it is empty" in
    assert(
      new Stack().pop() == Left(Halt.StackUnderflow),
      "an empty pop is a halt the caller inspects, not an exception"
    )

  it should "accept exactly the limit" in {
    val stack = new Stack
    fill(stack, Stack.Limit)
    assert(stack.depth == Stack.Limit, "the limit is the number of elements permitted, not the first refused")
  }

  it should "report overflow on the push past the limit" in {
    val stack = new Stack
    fill(stack, Stack.Limit)
    assert(stack.push(w(0)) == Left(Halt.StackOverflow), "1025 is the first push that cannot be made")
  }

  "peek" should "read the top at depth zero without removing it" in {
    val stack = new Stack
    val _ = stack.push(w(1))
    val _ = stack.push(w(2))
    assert(stack.peek(0) == Right(w(2)) && stack.depth == 2, "peeking leaves the stack as it was")
  }

  it should "report underflow past the bottom" in
    assert(new Stack().peek(0) == Left(Halt.StackUnderflow), "there is no element below an empty stack")

  "memory" should "start empty" in
    assert(new Memory().size == 0, "nothing is allocated until something is written or requested")

  it should "grow in whole words" in {
    val memory = new Memory
    memory.ensure(1)
    assert(memory.size == Word.Width, "memory is measured in words, so one byte costs a whole one")
  }

  it should "read zeros past what was written" in
    assert(
      new Memory().read(0, 4) == Bytes.fromIArray(IArray[Byte](0, 0, 0, 0)),
      "unwritten memory reads as zero rather than failing"
    )

  it should "return what was written at the same offset" in {
    val memory = new Memory
    memory.write(3, Bytes.fromIArray(IArray[Byte](9, 9)))
    assert(memory.read(3, 2) == Bytes.fromIArray(IArray[Byte](9, 9)), "a write is visible at the offset it was made")
  }

  it should "never shrink" in {
    val memory = new Memory
    memory.ensure(64)
    memory.ensure(1)
    assert(memory.size == 64, "memory only extends within a frame, so a smaller request is not a release")
  }

  "the memory cost" should "be zero for no memory" in
    assert(GasCost.total(0) == BigInt(0), "an unused memory costs nothing")

  it should "charge three per word while the quadratic term is still zero" in
    assert(GasCost.total(32) == BigInt(3), "one word is 1*3 + 1/512, and integer division makes the second term vanish")

  it should "round a partial word up" in
    assert(GasCost.total(33) == GasCost.total(64), "33 bytes occupies two words, and is charged as two")

  it should "grow faster than linearly once the quadratic term bites" in {
    val words = BigInt(1024)
    assert(
      GasCost.total(words * 32) == words * 3 + (words * words) / 512,
      "the specification's form is words*3 + words^2/512, and at 1024 words the second term is 2048 rather than nothing"
    )
  }

  "the expansion cost" should "be zero when the memory already reaches" in
    assert(
      GasCost.expansion(64, 32) == BigInt(0),
      "cost follows the high-water mark, so an access below it is already paid for"
    )

  it should "charge only the difference" in
    assert(
      GasCost.expansion(32, 64) == GasCost.total(64) - GasCost.total(32),
      "an operation pays for the growth it causes, not for the whole"
    )

  "ceil32" should "leave a whole word alone" in
    assert(GasCost.ceil32(32) == 32, "a value already on a word boundary does not round up to the next one")
