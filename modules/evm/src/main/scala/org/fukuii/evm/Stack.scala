package org.fukuii.evm

import scala.collection.mutable

/** The operand stack: words, bounded, last in first out.
  *
  * ==It holds words rather than bytes, and that is a surveyed choice==
  *
  * `ethereum/execution-specs` @ `ccaaaba58` holds `List[U256]`,
  * `ethereum/go-ethereum` @ `6bb0588ad` holds `[]uint256.Int`, and
  * `bluealloy/revm` @ `3064c0901c` holds `Vec<U256>`. `besu-eth/besu` @
  * `c2addd9424` holds `FlexStack<Bytes>` and converts for arithmetic — the one
  * byte-based stack among the four read. This follows the specification and
  * three of those four: a stack of [[Word]] means the arithmetic operations take
  * and return the type they compute in, and nothing converts on the hot path.
  *
  * Errors are values rather than exceptions, which is this codebase's existing
  * shape for a fallible read. The specification raises instead; the difference
  * is idiom, and the halts raised are the same ones.
  */
final class Stack:

  private val items: mutable.ArrayBuffer[Word] = mutable.ArrayBuffer.empty

  def depth: Int = items.length

  def isEmpty: Boolean = items.isEmpty

  def push(value: Word): Either[Halt, Unit] =
    if items.length >= Stack.Limit then Left(Halt.StackOverflow)
    else
      val _ = items.append(value)
      Right(())

  def pop(): Either[Halt, Word] =
    if items.isEmpty then Left(Halt.StackUnderflow)
    else Right(items.remove(items.length - 1))

  /** The element `depthFromTop` places below the top, without removing it. A
    * depth of zero is the top itself.
    */
  def peek(depthFromTop: Int): Either[Halt, Word] =
    if depthFromTop < 0 || depthFromTop >= items.length then Left(Halt.StackUnderflow)
    else Right(items(items.length - 1 - depthFromTop))

  /** Exchanges the top with the element `depthFromTop` places below it.
    *
    * A depth of zero would exchange the top with itself, which no operation
    * asks for: the shallowest exchange the machine has names the element one
    * below the top.
    */
  def swap(depthFromTop: Int): Either[Halt, Unit] =
    if depthFromTop <= 0 || depthFromTop >= items.length then Left(Halt.StackUnderflow)
    else
      val top = items.length - 1
      val other = top - depthFromTop
      val held = items(top)
      items(top) = items(other)
      items(other) = held
      Right(())

object Stack:

  /** The specification's `len(stack) == 1024` check, and the same value its
    * interpreter carries as `STACK_DEPTH_LIMIT`.
    */
  val Limit: Int = 1024
