package org.fukuii.evm

import org.fukuii.bytes.Bytes

/** Byte-addressed scratch memory, zero-filled and grown in whole words.
  *
  * ==Growing is not this type's decision==
  *
  * Expansion is charged for before it happens, and the charge is
  * [[GasCost.expansion]]. The specification draws the same split: its
  * `calculate_gas_extend_memory` computes the cost and the interpreter applies
  * it before the write lands.
  *
  * So this type does grow — [[write]] calls [[ensure]] — and the discipline is
  * on the caller rather than on the type: growth here is always growth someone
  * has already paid for, because an allocation nobody charged for is gas the
  * machine gave away. [[read]] never grows at all and zero-fills instead, since
  * reading past the mark reveals nothing that had to be allocated.
  */
final class Memory:

  private var bytes: Array[Byte] = Array.empty

  /** Size in bytes, always a multiple of 32 once anything has been written. */
  def size: Int = bytes.length

  /** Size in 32-byte words, which is the unit the cost is quoted in. */
  def sizeInWords: Int = bytes.length / Word.Width

  /** Grows to at least `required` bytes, rounded up to a whole word. Shrinking
    * never happens: memory only ever extends within a frame.
    */
  def ensure(required: Int): Unit =
    if required > bytes.length then
      val rounded = GasCost.ceil32(required)
      val grown = new Array[Byte](rounded)
      System.arraycopy(bytes, 0, grown, 0, bytes.length)
      bytes = grown

  /** `size` bytes from `offset`, zero-filled where they fall past what has been
    * written. The caller has already paid for the expansion.
    */
  def read(offset: Int, size: Int): Bytes =
    val out = new Array[Byte](size)
    val available = math.max(0, math.min(size, bytes.length - offset))
    if available > 0 then System.arraycopy(bytes, offset, out, 0, available)
    Bytes.fromIArray(IArray.unsafeFromArray(out))

  /** Writes `value` at `offset`, growing first if it does not fit. */
  def write(offset: Int, value: Bytes): Unit =
    val raw = value.toIArray
    if raw.nonEmpty then
      ensure(offset + raw.length)
      var i = 0
      while i < raw.length do
        bytes(offset + i) = raw(i)
        i += 1
