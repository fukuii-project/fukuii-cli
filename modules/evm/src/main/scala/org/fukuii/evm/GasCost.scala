package org.fukuii.evm

/** Gas arithmetic that is not tied to one operation.
  *
  * ==Amounts are arbitrary precision, deliberately==
  *
  * The memory cost is quadratic in the number of words, and an offset the
  * machine will happily accept is a 256-bit value — so the cost of an
  * unaffordable expansion is itself enormous, and computing it in a 64-bit
  * quantity would overflow into a small number and make the unaffordable look
  * cheap. The executable specification computes in unbounded `Uint` for this
  * reason. Production clients instead use a 64-bit counter with an explicit
  * bound checked before the multiply; that is the same defense reached the
  * other way, and it is the shape to adopt if this ever needs the speed.
  */
object GasCost:

  /** Gas per word of memory, from the specification's `MEMORY_PER_WORD`. */
  val MemoryPerWord: BigInt = BigInt(3)

  private val QuadraticDivisor: BigInt = BigInt(512)

  /** Rounds up to the next whole 32 bytes. */
  def ceil32(value: Int): Int =
    val remainder = value % Word.Width
    if remainder == 0 then value else value + (Word.Width - remainder)

  /** The total cost of holding `sizeInBytes` of memory, which is linear in the
    * word count plus a quadratic term. Total rather than incremental — see
    * [[expansion]], which is what an operation actually pays.
    */
  def total(sizeInBytes: BigInt): BigInt =
    val words = (sizeInBytes + Word.Width - 1) / Word.Width
    words * MemoryPerWord + (words * words) / QuadraticDivisor

  /** What an operation pays to take memory from `current` bytes to `required`.
    *
    * Zero when the memory already reaches that far, because the cost is a
    * function of the high-water mark rather than of each access: memory is
    * never released within a frame, so an access below the mark has already
    * been paid for.
    */
  def expansion(current: BigInt, required: BigInt): BigInt =
    if required <= current then BigInt(0) else total(required) - total(current)
