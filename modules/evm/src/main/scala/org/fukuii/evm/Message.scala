package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}

/** What one invocation was asked to do: who asked, as whom it runs, what came
  * with it.
  *
  * ==`currentTarget` is which account this runs AS, not which one was named==
  *
  * The two are the same for an ordinary call and separate for the forms that
  * borrow another account's code, so an operation reading storage or reporting
  * its own address has to name which of them it means. Every such operation at
  * this fork means this one: storage is read and written under it, and
  * `ADDRESS` reports it. The specification draws the same distinction under the
  * same name, and keeping it now costs a field rather than a later correction
  * at every site that reads one.
  *
  * @param data
  *   the input the invocation was called with, which the `CALLDATA` operations
  *   read. Reading past its end is not an error -- the specification pads with
  *   zeroes rather than refusing, so a short input is short and never missing.
  * @param depth
  *   how many invocations deep this one is, counting the outermost as zero. It
  *   rides here rather than on the frame because it is settled by whoever asked
  *   for the invocation and never moves while it runs, which is the same reason
  *   the caller and the value do. The operations that nest refuse rather than
  *   exceed [[Stack.Limit]], and the specification carries it in the same place.
  */
final case class Message(
    caller: Address,
    currentTarget: Address,
    value: Word,
    data: Bytes,
    depth: Int = 0
)
