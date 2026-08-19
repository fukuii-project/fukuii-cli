package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}

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
  def run(
      frame: Frame,
      table: OpcodeTable,
      schedule: GasSchedule,
      environment: Environment
  ): Either[Unsupported, Outcome] =
    var fault: Option[Fault] = None
    while fault.isEmpty && frame.running && frame.pc < frame.code.length do
      val code = frame.code.byteAt(frame.pc)
      table.operationAt(code) match
        case None            => fault = Some(Fault.Exceptional(Halt.InvalidOpcode(code)))
        case Some(operation) =>
          step(frame, operation, schedule, environment) match
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

  private def step(
      frame: Frame,
      operation: Operation,
      schedule: GasSchedule,
      environment: Environment
  ): Either[Fault, Unit] =
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

      // ── What this invocation is ────────────────────────────────────────────

      case Opcode.Address   => pushing(frame, operation)(wordOf(frame.message.currentTarget))
      case Opcode.Caller    => pushing(frame, operation)(wordOf(frame.message.caller))
      case Opcode.CallValue => pushing(frame, operation)(frame.message.value)

      // ── What the transaction and the block are ─────────────────────────────

      case Opcode.Origin     => pushing(frame, operation)(wordOf(environment.transaction.origin))
      case Opcode.GasPrice   => pushing(frame, operation)(Word(environment.transaction.gasPrice))
      case Opcode.Coinbase   => pushing(frame, operation)(wordOf(environment.block.coinbase))
      case Opcode.Timestamp  => pushing(frame, operation)(Word(environment.block.timestamp))
      case Opcode.Number     => pushing(frame, operation)(Word(environment.block.number))
      case Opcode.Difficulty => pushing(frame, operation)(Word(environment.block.difficulty))
      case Opcode.GasLimit   => pushing(frame, operation)(Word(environment.block.gasLimit))

      case Opcode.BlockHash =>
        priced(operation) { gas =>
          for
            requested <- frame.stack.pop()
            _ <- frame.charge(gas)
            _ <- frame.stack.push(blockHashFor(environment, requested))
          yield advance(frame)
        }

      // ── The input this invocation was called with ──────────────────────────

      case Opcode.CallDataLoad =>
        priced(operation) { gas =>
          for
            offset <- frame.stack.pop()
            _ <- frame.charge(gas)
            _ <- frame.stack.push(Word.fromBytes(bufferRead(frame.message.data, offset.toBigInt, Word.Width)))
          yield advance(frame)
        }

      case Opcode.CallDataSize =>
        pushing(frame, operation)(Word(BigInt(frame.message.data.length)))

      case Opcode.CallDataCopy =>
        exceptional(copyInto(frame, schedule.veryLow, schedule)(frame.message.data))

      // ── The code this invocation is running ────────────────────────────────

      case Opcode.CodeSize =>
        pushing(frame, operation)(Word(BigInt(frame.code.length)))

      case Opcode.CodeCopy =>
        exceptional(copyInto(frame, schedule.veryLow, schedule)(frame.code.bytes))

      // ── Another account ────────────────────────────────────────────────────

      case Opcode.Balance =>
        priced(operation) { gas =>
          for
            operand <- frame.stack.pop()
            _ <- frame.charge(gas)
            _ <- frame.stack.push(environment.world.balanceOf(addressOf(operand)))
          yield advance(frame)
        }

      case Opcode.ExtCodeSize =>
        priced(operation) { gas =>
          for
            operand <- frame.stack.pop()
            _ <- frame.charge(gas)
            _ <- frame.stack.push(Word(BigInt(environment.world.codeOf(addressOf(operand)).length)))
          yield advance(frame)
        }

      // The account is taken before the three operands every copying operation
      // shares, so it is popped here and the rest is the shared shape. Its code
      // is read inside that shape, which is after the charge -- the order the
      // specification puts it in, and the one that keeps an unaffordable copy
      // from reaching the state seam at all.
      case Opcode.ExtCodeCopy =>
        exceptional(
          for
            operand <- frame.stack.pop()
            copied <- copyInto(frame, schedule.externalBase, schedule)(
              environment.world.codeOf(addressOf(operand))
            )
          yield copied
        )

      // ── Storage ────────────────────────────────────────────────────────────

      case Opcode.SLoad =>
        priced(operation) { gas =>
          for
            slot <- frame.stack.pop()
            _ <- frame.charge(gas)
            _ <- frame.stack.push(environment.world.storageAt(frame.message.currentTarget, slot))
          yield advance(frame)
        }

      // Priced from what the slot already holds, so the read comes before the
      // charge and the charge before the write. Setting a slot that held
      // nothing is the expensive case; every other combination, including
      // clearing one, is the cheaper one.
      case Opcode.SStore =>
        exceptional(
          for
            slot <- frame.stack.pop()
            value <- frame.stack.pop()
            held = environment.world.storageAt(frame.message.currentTarget, slot)
            _ = refundIfCleared(frame, schedule, held, value)
            _ <- frame.charge(if held.isZero && !value.isZero then schedule.storageSet else schedule.storageReset)
          yield
            environment.world.setStorage(frame.message.currentTarget, slot, value)
            advance(frame)
        )

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

  /** An operation that takes nothing and answers with one value.
    *
    * The value is read after the charge is made, which is the specification's
    * order. It is invisible for every operation using this, because none of
    * them reads anything the charge changes -- `GAS` is the one that does, and
    * it is written out separately for that reason rather than being made to fit
    * here.
    */
  private def pushing(frame: Frame, operation: Operation)(value: => Word): Either[Fault, Unit] =
    priced(operation) { gas =>
      for
        _ <- frame.charge(gas)
        _ <- frame.stack.push(value)
      yield advance(frame)
    }

  private def advance(frame: Frame): Unit = frame.pc += 1

  /** The low twenty bytes of an operand, which is how every operation naming an
    * account reads one off the stack. A word is wider than an address and the
    * specification masks rather than refusing, so an operand with rubbish above
    * the twentieth byte names an ordinary account.
    */
  private def addressOf(operand: Word): Address =
    Address.fromBytesTruncating(operand.toBytes.toIArray)

  private def wordOf(address: Address): Word =
    Word.fromBytes(Bytes.fromIArray(address.toBytes))

  /** The hash of an earlier block, and zero outside the window this fork
    * allows.
    *
    * The comparison is made at arbitrary precision. An operand near the top of
    * the range would wrap if the window were added to it in a machine word, and
    * the window would then admit a block that must answer zero.
    */
  private def blockHashFor(environment: Environment, requested: Word): Word =
    val wanted = requested.toBigInt
    val current = environment.block.number
    if wanted < current && current <= wanted + BlockHashReach then
      Word.fromBytes(Bytes.fromIArray(environment.blockHashAt(wanted).toBytes))
    else Word.Zero

  /** How far back a block hash can be read. */
  private val BlockHashReach: BigInt = BigInt(256)

  private def refundIfCleared(frame: Frame, schedule: GasSchedule, held: Word, value: Word): Unit =
    if value.isZero && !held.isZero then frame.refundCounter += schedule.refundStorageClear

  /** The shape every copying operation shares: three operands, a price in whole
    * words of the amount copied, and a source that is read only once the copy
    * has been paid for.
    *
    * `source` is by name for that last reason. Reading an account's code is a
    * state lookup, and doing it before the charge would let an operation that
    * cannot afford to run still reach the state seam.
    */
  private def copyInto(frame: Frame, base: BigInt, schedule: GasSchedule)(
      source: => Bytes
  ): Either[Halt, Unit] =
    for
      memoryStart <- frame.stack.pop()
      sourceStart <- frame.stack.pop()
      size <- frame.stack.pop()
      start <- copyCharge(frame, base, schedule, memoryStart, size)
    yield
      if !size.isZero then frame.memory.write(start, bufferRead(source, sourceStart.toBigInt, size.toBigInt.toInt))
      advance(frame)

  /** Charges a copy and grows memory for it, answering where the copy lands.
    *
    * A copy of nothing pays the settled part and no more: the specification
    * skips the extension outright for a zero size, so a zero-length copy at an
    * offset no memory could reach is affordable rather than being charged for
    * memory it never touches.
    */
  private def copyCharge(
      frame: Frame,
      base: BigInt,
      schedule: GasSchedule,
      offset: Word,
      size: Word
  ): Either[Halt, Int] =
    val words = (size.toBigInt + Word.Width - 1) / Word.Width
    val settled = base + schedule.copyPerWord * words
    if size.isZero then frame.charge(settled).map(_ => 0)
    else expand(frame, settled, offset, size.toBigInt)

  /** `size` bytes of `source` from `start`, zero-filled where it runs out.
    *
    * Reading past the end is not a fault: the specification pads rather than
    * refusing, so a read wholly beyond the source is a run of zeros. `start` is
    * a full-width operand and stays arbitrary precision until it is known to
    * be inside the source, because narrowing it first is how a huge offset
    * would come to read from a small one.
    */
  private def bufferRead(source: Bytes, start: BigInt, size: Int): Bytes =
    val raw = source.toIArray
    val out = new Array[Byte](size)
    if start < BigInt(raw.length) then
      val from = start.toInt
      val available = math.min(size, raw.length - from)
      var i = 0
      while i < available do
        out(i) = raw(from + i)
        i += 1
    Bytes.fromIArray(IArray.unsafeFromArray(out))

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
