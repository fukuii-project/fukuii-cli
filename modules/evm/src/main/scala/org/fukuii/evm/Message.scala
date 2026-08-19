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
  */
final case class Message(
    caller: Address,
    currentTarget: Address,
    value: Word,
    data: Bytes
)
