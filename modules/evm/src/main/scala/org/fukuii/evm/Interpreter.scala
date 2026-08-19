package org.fukuii.evm

import org.fukuii.bytes.Bytes

/** Runs a program against a frame, one operation at a time.
  *
  * ==Nothing here asks which network or which fork==
  *
  * The operations that exist and what they cost arrive as an [[OpcodeTable]]
  * and a [[GasSchedule]]. That is the whole of the configuration seam: a
  * network is a pair of those values, not a branch in this file, and a network
  * named anywhere below would mean the seam had been crossed.
  *
  * ==The loop ends three ways and only two of them are results==
  *
  * Running off the end of the code is a normal stop, exactly as `STOP` is --
  * the specification's loop condition is the program counter against the code
  * length, with no terminator required. An exceptional halt ends execution with
  * every remaining unit of gas consumed. The third is [[Unsupported]], which is
  * not an end the machine can reach on any chain: it says this build cannot run
  * an entry the table admits, and it is a different type for that reason.
  *
  * ==Order within an operation is the specification's, not a convenience==
  *
  * Operands are taken, then gas is charged, then the operation acts, then the
  * program counter moves -- except where the specification itself charges
  * first, which the duplicating and exchanging operations do. At this fork
  * every exceptional halt costs the same, so the order changes only which
  * reason is reported; it is followed anyway, because the reason is what a
  * divergence is diagnosed from.
  */
object Interpreter:

  /** Runs until the program stops, halts, or meets something unbuilt. */
  def run(frame: Frame, table: OpcodeTable, schedule: GasSchedule): Either[Unsupported, Outcome] =
    var fault: Option[Fault] = None
    while fault.isEmpty && frame.running && frame.pc < frame.code.length do
      val code = frame.code.byteAt(frame.pc)
      table.operationAt(code) match
        case None            => fault = Some(Fault.Exceptional(Halt.InvalidOpcode(code)))
        case Some(operation) =>
          step(frame, operation, schedule) match
            case Left(met) => fault = Some(met)
            case Right(()) => ()
    fault match
      case None                          => Right(Outcome.Stopped(frame.gasLeft, frame.output))
      case Some(Fault.Exceptional(halt)) =>
        // The frame is left where a caller would read it, and at this fork an
        // exceptional halt keeps nothing: a remainder still showing here is gas
        // the caller would hand back twice.
        frame.gasLeft = BigInt(0)
        Right(Outcome.Halted(halt))
      case Some(Fault.NotBuilt(opcode)) => Left(Unsupported(opcode))

  /** What stopped the loop, separating a chain-reachable end from a gap in this
    * build. Private because only [[run]] may decide which of the two a caller
    * sees.
    */
  private enum Fault:
    case Exceptional(halt: Halt)
    case NotBuilt(opcode: Opcode)

  private def step(frame: Frame, operation: Operation, schedule: GasSchedule): Either[Fault, Unit] =
    operation.opcode match

      case Opcode.Stop =>
        priced(operation) { gas =>
          for _ <- frame.charge(gas)
          yield
            frame.running = false
            advance(frame)
        }

      case Opcode.Add    => binary(frame, operation)((x, y) => x.add(y))
      case Opcode.Sub    => binary(frame, operation)((x, y) => x.sub(y))
      case Opcode.Mul    => binary(frame, operation)((x, y) => x.mul(y))
      case Opcode.Div    => binary(frame, operation)((x, y) => x.div(y))
      case Opcode.SDiv   => binary(frame, operation)((x, y) => x.sdiv(y))
      case Opcode.Mod    => binary(frame, operation)((x, y) => x.mod(y))
      case Opcode.SMod   => binary(frame, operation)((x, y) => x.smod(y))
      case Opcode.AddMod => ternary(frame, operation)((x, y, z) => x.addMod(y, z))
      case Opcode.MulMod => ternary(frame, operation)((x, y, z) => x.mulMod(y, z))

      // The exponent's own size is charged for, so this price is not one the
      // table can hold.
      case Opcode.Exp =>
        exceptional(
          for
            base <- frame.stack.pop()
            exponent <- frame.stack.pop()
            _ <- frame.charge(schedule.expBase + schedule.expPerByte * byteLength(exponent))
            _ <- frame.stack.push(base.exp(exponent))
          yield advance(frame)
        )

      // The operand naming the width is on top, so it is taken first and is the
      // operation's second argument.
      case Opcode.SignExtend => binary(frame, operation)((byteIndex, value) => value.signExtend(byteIndex))

      case Opcode.Lt     => binary(frame, operation)((x, y) => flag(x.lessThan(y)))
      case Opcode.Gt     => binary(frame, operation)((x, y) => flag(x.greaterThan(y)))
      case Opcode.SLt    => binary(frame, operation)((x, y) => flag(x.signedLessThan(y)))
      case Opcode.SGt    => binary(frame, operation)((x, y) => flag(x.signedGreaterThan(y)))
      case Opcode.Eq     => binary(frame, operation)((x, y) => flag(x == y))
      case Opcode.IsZero => unary(frame, operation)(x => flag(x.isZero))

      case Opcode.And  => binary(frame, operation)((x, y) => x.and(y))
      case Opcode.Or   => binary(frame, operation)((x, y) => x.or(y))
      case Opcode.Xor  => binary(frame, operation)((x, y) => x.xor(y))
      case Opcode.Not  => unary(frame, operation)(x => x.not)
      case Opcode.Byte => binary(frame, operation)((index, word) => word.byte(index))

      case Opcode.Pop =>
        priced(operation) { gas =>
          for
            _ <- frame.stack.pop()
            _ <- frame.charge(gas)
          yield advance(frame)
        }

      case Opcode.MSize =>
        pushing(frame, operation)(Word(BigInt(frame.memory.size)))

      case Opcode.Pc =>
        pushing(frame, operation)(Word(BigInt(frame.pc)))

      case Opcode.JumpDest =>
        priced(operation)(gas => frame.charge(gas).map(_ => advance(frame)))

      case Opcode.Jump =>
        priced(operation) { gas =>
          for
            destination <- frame.stack.pop()
            _ <- frame.charge(gas)
            landed <- jumpTo(frame, destination)
          yield landed
        }

      case Opcode.JumpI =>
        priced(operation) { gas =>
          for
            destination <- frame.stack.pop()
            condition <- frame.stack.pop()
            _ <- frame.charge(gas)
            landed <- if condition.isZero then Right(advance(frame)) else jumpTo(frame, destination)
          yield landed
        }

      case Opcode.MLoad =>
        exceptional(
          for
            offset <- frame.stack.pop()
            start <- expand(frame, schedule.veryLow, offset, BigInt(Word.Width))
            _ <- frame.stack.push(Word.fromBytes(frame.memory.read(start, Word.Width)))
          yield advance(frame)
        )

      case Opcode.MStore =>
        exceptional(
          for
            offset <- frame.stack.pop()
            value <- frame.stack.pop()
            start <- expand(frame, schedule.veryLow, offset, BigInt(Word.Width))
          yield
            frame.memory.write(start, value.toBytes)
            advance(frame)
        )

      case Opcode.MStore8 =>
        exceptional(
          for
            offset <- frame.stack.pop()
            value <- frame.stack.pop()
            start <- expand(frame, schedule.veryLow, offset, BigInt(1))
          yield
            frame.memory.write(start, lowByte(value))
            advance(frame)
        )

      // Charged before it is read, so what is pushed is what remains once this
      // operation has been paid for.
      case Opcode.Gas =>
        priced(operation) { gas =>
          for
            _ <- frame.charge(gas)
            _ <- frame.stack.push(Word(frame.gasLeft))
          yield advance(frame)
        }

      case push if Opcode.isPush(push) =>
        val width = Opcode.immediateWidth(push)
        priced(operation) { gas =>
          for
            _ <- frame.charge(gas)
            _ <- frame.stack.push(Word.fromBytes(frame.code.read(frame.pc + 1, width)))
          yield frame.pc += 1 + width
        }

      // Gas before the depth check, which is the specification's order here and
      // the opposite of the one it uses when an operand is taken.
      case dup if dup.code >= Opcode.Dup1.code && dup.code <= Opcode.Dup16.code =>
        priced(operation) { gas =>
          for
            _ <- frame.charge(gas)
            held <- frame.stack.peek(dup.code - Opcode.Dup1.code)
            _ <- frame.stack.push(held)
          yield advance(frame)
        }

      case swap if swap.code >= Opcode.Swap1.code && swap.code <= Opcode.Swap16.code =>
        priced(operation) { gas =>
          for
            _ <- frame.charge(gas)
            _ <- frame.stack.swap(swap.code - Opcode.Swap1.code + 1)
          yield advance(frame)
        }

      case unbuilt => Left(Fault.NotBuilt(unbuilt))

  /** Runs `body` with the operation's settled price, or reports that this build
    * cannot run the entry.
    *
    * A table saying an operation computes its own price, where the operation
    * below does not, is a mismatch between the table and this build -- the same
    * condition as an operation with no implementation, and reported the same
    * way. It is deliberately not turned into a halt: a halt is a result a chain
    * can reach, and this is not.
    */
  private def priced(operation: Operation)(body: BigInt => Either[Halt, Unit]): Either[Fault, Unit] =
    operation.cost match
      case Cost.Fixed(gas) => exceptional(body(gas))
      case Cost.Computed   => Left(Fault.NotBuilt(operation.opcode))

  private def unary(frame: Frame, operation: Operation)(f: Word => Word): Either[Fault, Unit] =
    priced(operation) { gas =>
      for
        x <- frame.stack.pop()
        _ <- frame.charge(gas)
        _ <- frame.stack.push(f(x))
      yield advance(frame)
    }

  private def binary(frame: Frame, operation: Operation)(f: (Word, Word) => Word): Either[Fault, Unit] =
    priced(operation) { gas =>
      for
        x <- frame.stack.pop()
        y <- frame.stack.pop()
        _ <- frame.charge(gas)
        _ <- frame.stack.push(f(x, y))
      yield advance(frame)
    }

  private def ternary(frame: Frame, operation: Operation)(f: (Word, Word, Word) => Word): Either[Fault, Unit] =
    priced(operation) { gas =>
      for
        x <- frame.stack.pop()
        y <- frame.stack.pop()
        z <- frame.stack.pop()
        _ <- frame.charge(gas)
        _ <- frame.stack.push(f(x, y, z))
      yield advance(frame)
    }

  /** An operation that takes nothing and answers with one value read from the
    * frame, where the value is settled before the charge is made.
    */
  private def pushing(frame: Frame, operation: Operation)(value: => Word): Either[Fault, Unit] =
    priced(operation) { gas =>
      for
        _ <- frame.charge(gas)
        _ <- frame.stack.push(value)
      yield advance(frame)
    }

  private def advance(frame: Frame): Unit = frame.pc += 1

  private def flag(condition: Boolean): Word = if condition then Word.One else Word.Zero

  private def lowByte(value: Word): Bytes =
    Bytes.fromArray(Array((value.toBigInt & BigInt(0xff)).toByte))

  /** How many bytes the value occupies, which is what an exponent is priced by. */
  private def byteLength(value: Word): BigInt =
    BigInt((value.toBigInt.bitLength + 7) / 8)

  private def exceptional(result: Either[Halt, Unit]): Either[Fault, Unit] =
    result.left.map(Fault.Exceptional.apply)

  private def jumpTo(frame: Frame, destination: Word): Either[Halt, Unit] =
    val target = destination.toBigInt
    if target > MaxReach || !frame.code.validJumpDestinations.contains(target.toInt) then
      Left(Halt.InvalidJumpDestination)
    else
      frame.pc = target.toInt
      Right(())

  /** Charges `base` plus what it costs to reach `offset + size` bytes of
    * memory, grows to it, and answers the offset as an index.
    *
    * The bound is not a policy: memory is indexed by `Int`, and reaching past
    * that costs more gas than any schedule can express, so it is refused as
    * unaffordable rather than as too large.
    */
  private def expand(frame: Frame, base: BigInt, offset: Word, size: BigInt): Either[Halt, Int] =
    val reach = offset.toBigInt + size
    for
      _ <- frame.charge(base + GasCost.expansion(BigInt(frame.memory.size), reach))
      start <- if reach > MaxReach then Left(Halt.OutOfGas) else Right((reach - size).toInt)
    yield
      frame.memory.ensure(reach.toInt)
      start

  private val MaxReach: BigInt = BigInt(Int.MaxValue - Word.Width)
