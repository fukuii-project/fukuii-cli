package org.fukuii.evm

import org.fukuii.bytes.Bytes

/** One invocation's working state: where it is, what it holds, and what it has
  * left to spend.
  *
  * ==Mutable, following the specification and what sits below it==
  *
  * The executable specification's frame is a mutable record and every operation
  * updates it in place; [[Stack]] and [[Memory]] here are already mutable for
  * the same reason. A frame rebuilt per instruction would allocate once per
  * operation to express a machine that is definitionally a state that changes.
  *
  * ==Gas leaves only through charge==
  *
  * [[gasLeft]] is readable but is reduced only by [[charge]], which refuses
  * rather than going negative. An operation that spends by assignment can
  * overspend, and an overspent frame is indistinguishable afterwards from one
  * that was affordable.
  */
final class Frame(val code: Code, initialGas: BigInt):

  /** The position of the instruction about to run. */
  var pc: Int = 0

  var gasLeft: BigInt = initialGas

  /** False once an operation has ended execution deliberately. */
  var running: Boolean = true

  /** What the invocation hands back. Empty until an operation sets it, which
    * at this fork only `RETURN` does.
    */
  var output: Bytes = Bytes.Empty

  val stack: Stack = new Stack

  val memory: Memory = new Memory

  /** Spends `amount`, or reports that it could not be spent.
    *
    * The check is before the subtraction rather than after it, because at this
    * fork a frame that cannot pay keeps nothing: the caller consumes the
    * remainder, so a negative balance would be arithmetic nobody ever reads.
    */
  def charge(amount: BigInt): Either[Halt, Unit] =
    if gasLeft < amount then Left(Halt.OutOfGas)
    else
      gasLeft -= amount
      Right(())
