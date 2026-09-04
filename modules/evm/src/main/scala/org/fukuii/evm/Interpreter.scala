package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.types.Log

/** Runs one invocation, and the operations it is made of.
  *
  * ==An invocation is more than its loop==
  *
  * Running the code is the middle of it. Around that sit the account being
  * brought into being, the value being moved, and -- where anything goes wrong
  * -- all of it being undone. Those are here rather than at each call site
  * because the operations that start a nested invocation call straight back
  * into this, so a caller that assembled them itself would be assembling them
  * once per operation.
  *
  * ==Nothing here asks which network or which fork==
  *
  * The operations that exist, what they cost, and which addresses answer
  * natively arrive as an [[OpcodeTable]], a [[GasSchedule]] and a
  * [[PrecompileSet]]. That is the whole of the configuration seam: a network is
  * those three values, not a branch in this file, and a network named anywhere
  * below would mean the seam had been crossed.
  *
  * ==The loop ends four ways and only three of them are results==
  *
  * Running off the end of the code is a normal stop, exactly as `STOP` is --
  * the specification's loop condition is the program counter against the code
  * length, with no terminator required. An exceptional halt ends execution with
  * every remaining unit of gas consumed. A revert ends it keeping the
  * remainder and a payload, and is a failure for every other purpose. The
  * fourth is [[Unsupported]], which is not an end the machine can reach on any
  * chain: it says this build cannot run an entry the table admits, and it is a
  * different type for that reason.
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

  /** Runs one invocation: gives it the account it runs as, moves any value it
    * carries, executes its code, and undoes all of that if it does not end
    * normally.
    *
    * ==The undo is the reason this is not just the loop==
    *
    * An invocation that fails leaves no trace, whether it halted or reverted,
    * and that has to be true of the outermost invocation as much as of a
    * nested one -- the
    * specification takes its snapshot inside the one function both paths go
    * through, and go-ethereum marks its journal in the same place. A caller that
    * ran the loop directly and reverted afterwards would be re-implementing this
    * once per call site, and would have to get the ordering right each time: the
    * snapshot is taken before the account is brought into being, so a failed
    * invocation does not leave one behind.
    *
    * An entry the table admits and this build cannot run is undone too. It is
    * not a chain outcome, so nothing above can incorporate it, and leaving a
    * half-written world behind would make it look like one.
    *
    * ==A precompile replaces the loop and nothing around it==
    *
    * The account is still brought into being, the value is still moved, and a
    * failure is still undone -- the branch sits exactly where the loop would
    * have run, which is where both sources put it. Putting it in the operation
    * that starts a nested invocation instead would have left the outermost one
    * unable to reach a precompile at all, and a transaction sent straight to
    * one is the ordinary way it is used.
    */
  def run(
      frame: Frame,
      environment: Environment
  ): Either[Unsupported, Outcome] =
    val precompiles = environment.precompiles
    if frame.message.depth > Stack.Limit then
      frame.gasLeft = BigInt(0)
      Right(Outcome.Halted(Halt.StackDepthLimit))
    else
      val world = environment.world
      val taken = world.snapshot()
      // Two acts that read as one. The first brings the account into being and
      // is reversed below; the second records that it was reached, and nothing
      // in this function undoes that.
      world.touch(frame.message.currentTarget)
      frame.touchedAccounts = frame.touchedAccounts + frame.message.currentTarget
      transfer(world, frame.message)
      val result = frame.message.codeAddress.flatMap(precompiles.at) match
        case Some(precompile) => Right(runNatively(frame, precompile))
        case None             => execute(frame, environment)
      // Written out rather than as a stop and a wildcard, so that a further way
      // for an invocation to end cannot be added without this deciding what it
      // does about the writes that invocation made.
      result match
        case Right(Outcome.Stopped(_, _))  => ()
        case Right(Outcome.Reverted(_, _)) => world.restore(taken)
        case Right(Outcome.Halted(_))      => world.restore(taken)
        case Left(_)                       => world.restore(taken)
      result

  /** Charges for a precompile and runs it, or halts because it cannot be paid
    * for or because the input has no answer.
    *
    * The charge comes first, so an invocation that cannot afford the answer
    * never computes it. A shortfall is an ordinary exceptional halt and keeps
    * nothing, which is the same rule [[execute]] applies to an operation that
    * cannot pay.
    *
    * A precompile that refuses its input keeps nothing either, and for the same
    * reason: every member of [[Halt]] is an exceptional halt. So the two arms
    * differ in the reason they carry and in nothing a caller can observe --
    * which is why the remainder is zeroed in both rather than only in the
    * first.
    */
  private def runNatively(frame: Frame, precompile: Precompile): Outcome =
    frame.charge(precompile.gasFor(frame.message.data)) match
      case Left(halt) =>
        frame.gasLeft = BigInt(0)
        Outcome.Halted(halt)
      case Right(()) =>
        precompile.run(frame.message.data) match
          case Left(halt) =>
            frame.gasLeft = BigInt(0)
            Outcome.Halted(halt)
          case Right(answer) =>
            frame.output = answer
            Outcome.Stopped(frame.gasLeft, frame.output)

  /** Moves an invocation's value from its caller to the account it runs as,
    * where the invocation is one that moves it at all.
    *
    * ==Carrying a value and moving it are different things==
    *
    * An invocation that borrows another account's code while keeping its
    * caller's identity carries the value it was itself invoked with, so the code
    * it runs reads the same figure -- and moves nothing, because that move was
    * already made by the invocation it is borrowing from. Moving it again takes
    * the same value out of the original caller twice.
    *
    * **The second move is silent far more often than not.** It raises only where
    * the original caller has since spent below the value; where it still holds
    * enough, the transfer simply succeeds and the state root is wrong with
    * nothing to report.
    *
    * The caller's balance was checked by whichever operation asked for the
    * invocation, so a shortfall here is a caller that did not check rather than
    * a state a chain can reach -- and the machine's word wraps, so an unchecked
    * subtraction would turn a shortfall into an enormous balance rather than
    * into a failure.
    */
  /** Whether these rules refuse to store code beginning with the byte they
    * reserve.
    *
    * ==Empty code is not refused, and the guard is the specification's==
    *
    * A deployment returning nothing has no leading byte to compare, and it is a
    * legal deployment at every fork. The specification writes the same guard as
    * a length test before the comparison
    * (`ethereum/execution-specs` @ `20f7f6271a`,
    * `forks/london/vm/interpreter.py`), and `ethereum/go-ethereum-pow` @
    * `v1.10.26` writes it as `len(ret) >= 1` in the same condition. Reading a
    * missing first byte as anything other than "no prefix to reserve" would
    * refuse every self-destructing constructor on the network.
    *
    * The comparison is unsigned. The reserved value exceeds what a signed byte
    * holds, so the byte read out of the code is widened before it is compared
    * rather than the reserved value being narrowed to meet it.
    */
  private def reservesPrefix(rules: EvmRules, code: Bytes): Boolean =
    rules.reservedCodePrefix.exists(reserved => code.nonEmpty && (code.toIArray(0) & 0xff) == reserved)

  /** A rule set holding the base-fee operation over a block carrying no base
    * fee, which is a configuration rather than a chain state.
    *
    * Raised rather than returned for the reason every other broken precondition
    * here is: there is no caller who could act on it, and nothing on a chain
    * produces it. The message names the block, because what has to be found is
    * the rule set that put the operation in the table without the header field
    * beside it.
    */
  private def unfilledBaseFee(environment: Environment): Nothing =
    throw new IllegalStateException(
      "the base-fee operation is in this table over a block that carries no base fee, at number " +
        environment.block.number.toString
    )

  private def transfer(world: JournaledWorldState, message: Message): Unit =
    if message.transfersValue && !message.value.isZero then
      val available = world.balanceOf(message.caller)
      if available.toBigInt < message.value.toBigInt then
        throw new IllegalStateException(
          "an invocation was started carrying more value than its caller holds, from " + message.caller.toHex
        )
      world.setBalance(message.caller, available.sub(message.value))
      world.setBalance(message.currentTarget, world.balanceOf(message.currentTarget).add(message.value))

  private def execute(
      frame: Frame,
      environment: Environment
  ): Either[Unsupported, Outcome] =
    val table = environment.table
    var fault: Option[Fault] = None
    while fault.isEmpty && frame.running && frame.pc < frame.code.length do
      val code = frame.code.byteAt(frame.pc)
      table.operationAt(code) match
        case None            => fault = Some(Fault.Exceptional(Halt.InvalidOpcode(code)))
        case Some(operation) =>
          step(frame, operation, environment) match
            case Left(met) => fault = Some(met)
            case Right(()) => ()
    fault match
      case None                          => Right(Outcome.Stopped(frame.gasLeft, frame.output))
      case Some(Fault.Exceptional(halt)) =>
        // The frame is left where a caller would read it, and an exceptional
        // halt keeps nothing: a remainder still showing here is gas the caller
        // would hand back twice.
        frame.gasLeft = BigInt(0)
        Right(Outcome.Halted(halt))
      // Nothing is taken and nothing is emptied, which is the whole of the
      // difference from the arm above.
      case Some(Fault.Reverted)         => Right(Outcome.Reverted(frame.gasLeft, frame.output))
      case Some(Fault.NotBuilt(opcode)) => Left(Unsupported(opcode))

  /** What stopped the loop, separating the chain-reachable ends from a gap in
    * this build. Private because only [[run]] may decide which of them a caller
    * sees.
    */
  private enum Fault:
    case Exceptional(halt: Halt)
    case Reverted
    case NotBuilt(opcode: Opcode)

  private def step(
      frame: Frame,
      operation: Operation,
      environment: Environment
  ): Either[Fault, Unit] =
    val schedule = environment.schedule
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

      // The shift distance is on top for all three, so it is taken first and is
      // the argument rather than the receiver -- the same inversion
      // `SIGNEXTEND` above already has, and the reason both read backwards
      // against their own names.
      case Opcode.Shl => binary(frame, operation)((shift, value) => value.shiftLeft(shift))
      case Opcode.Shr => binary(frame, operation)((shift, value) => value.shiftRight(shift))
      case Opcode.Sar => binary(frame, operation)((shift, value) => value.shiftRightArithmetic(shift))

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

      // Reading its own balance takes no operand, which is the whole reason the
      // proposal that adds it prices it three tiers below the one that reads
      // another account's: there is no address to resolve and no account to
      // reach for that the invocation is not already inside.
      case Opcode.SelfBalance =>
        pushing(frame, operation)(environment.world.balanceOf(frame.message.currentTarget))

      // ── What the transaction and the block are ─────────────────────────────

      // The one operation that reads which network this is. It is not a block
      // value and not a transaction value, so it comes from the environment
      // directly rather than through either context.
      case Opcode.ChainId    => pushing(frame, operation)(Word(environment.chainId.toBigInt))
      case Opcode.Origin     => pushing(frame, operation)(wordOf(environment.transaction.origin))
      case Opcode.GasPrice   => pushing(frame, operation)(Word(environment.transaction.gasPrice))
      case Opcode.Coinbase   => pushing(frame, operation)(wordOf(environment.block.coinbase))
      case Opcode.Timestamp  => pushing(frame, operation)(Word(environment.block.timestamp))
      case Opcode.Number     => pushing(frame, operation)(Word(environment.block.number))
      case Opcode.Difficulty => pushing(frame, operation)(Word(environment.block.difficulty))
      case Opcode.GasLimit   => pushing(frame, operation)(Word(environment.block.gasLimit))

      // The one block value that is absent below the fork which introduced it,
      // so reading it is the only block read that can find nothing. It is
      // refused rather than defaulted: zero is a legal base fee, so standing it
      // in for a field that is not there would push a plausible answer for a
      // block that never had one. What keeps the refusal out of reach is that
      // this operation joins the table at the same fork that fills the header
      // field, and the two are adopted together.
      case Opcode.BaseFee =>
        pushing(frame, operation)(Word(environment.block.baseFee.getOrElse(unfilledBaseFee(environment))))

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

      // ── What the last invocation this one started handed back ──────────────

      case Opcode.ReturnDataSize =>
        pushing(frame, operation)(Word(BigInt(frame.returnData.length)))

      // THE ONE COPYING OPERATION THAT REFUSES RATHER THAN PADS, so it shares
      // the family's charge and not its read. `copyInto` ends in `bufferRead`,
      // which zero-fills past the end of its source -- correct for the three
      // above and a silent state root difference here.
      //
      // The charge is settled before the refusal, which is the specification's
      // order (`ethereum/execution-specs` @ `20f7f6271a`,
      // `src/ethereum/forks/byzantium/vm/instructions/environment.py:434-441`).
      // Neither is observable, both ends taking everything; `besu-eth/besu` @
      // `fdf1247c6d` checks the bound first and reports a different reason.
      case Opcode.ReturnDataCopy =>
        exceptional(
          for
            memoryStart <- frame.stack.pop()
            sourceStart <- frame.stack.pop()
            size <- frame.stack.pop()
            start <- copyCharge(frame, schedule.veryLow, schedule, memoryStart, size)
            _ <- withinReturnData(frame, sourceStart, size)
          yield
            if !size.isZero then frame.memory.write(start, sliceOf(frame.returnData, sourceStart, size))
            advance(frame)
        )

      // ── Another account ────────────────────────────────────────────────────

      case Opcode.Balance =>
        reachingAnAccount(frame, operation, environment.rules) { (address, gas) =>
          for
            _ <- frame.charge(gas)
            _ <- frame.stack.push(environment.world.balanceOf(address))
          yield advance(frame)
        }

      case Opcode.ExtCodeSize =>
        reachingAnAccount(frame, operation, environment.rules) { (address, gas) =>
          for
            _ <- frame.charge(gas)
            _ <- frame.stack.push(Word(BigInt(environment.world.codeOf(address).length)))
          yield advance(frame)
        }

      // An EMPTY account answers zero rather than the hash of empty code, and
      // the two are different answers to different questions: the hash of no
      // code is a real digest that a codeless-but-funded account genuinely
      // has. `ethereum/execution-specs` @ `20f7f6271a`,
      // `forks/constantinople/vm/instructions/environment.py`, tests
      // `account == EMPTY_ACCOUNT` and pushes `U256(0)` only there.
      //
      // [[deadAt]] is that test and NOT [[deployableAt]]: emptiness here is
      // EIP-161's -- no count, no code, no balance -- and the sibling scaladoc
      // records why substituting one for the other is wrong on exactly the
      // addresses each was written for. A non-existent account satisfies it
      // too, which is what makes the specification's absent-account case fall
      // out rather than needing a branch of its own.
      case Opcode.ExtCodeHash =>
        reachingAnAccount(frame, operation, environment.rules) { (address, gas) =>
          for
            _ <- frame.charge(gas)
            _ <- frame.stack.push(
              if deadAt(environment.world, address) then Word.Zero
              else Word.fromBytes(Bytes.fromIArray(Keccak256.hash(environment.world.codeOf(address).toIArray).toBytes))
            )
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
            address = addressOf(operand)
            copied <- copyInto(
              frame,
              costOfReaching(frame, environment.rules, schedule.externalBase, address),
              schedule
            )(
              environment.world.codeOf(address)
            )
          yield copied
        )

      // ── Storage ────────────────────────────────────────────────────────────

      case Opcode.SLoad =>
        reachingASlot(frame, operation, environment.rules) { (slot, gas) =>
          for
            _ <- frame.charge(gas)
            _ <- frame.stack.push(environment.world.storageAt(frame.message.currentTarget, slot))
          yield advance(frame)
        }

      // The read comes before the charge and the charge before the write,
      // under every scheme.
      //
      // WHICH scheme is a fork's answer rather than the machine's, so what the
      // charge depends on is not fixed here: the legacy one reads only what the
      // slot holds now, and the two net ones also read what it held when the
      // transaction began. The two helpers carry the clauses and this comment
      // deliberately states none of them -- it said the legacy one's outright
      // until net metering landed, which made it a correct-looking description
      // of half the behavior.
      //
      // Three cases and two helpers, because the third differs from the second
      // by a refusal rather than by a clause.
      case Opcode.SStore =>
        exceptional(
          for
            slot <- frame.stack.pop()
            value <- frame.stack.pop()
            _ <- storageSentry(frame, schedule, environment.rules.storageMetering)
            target = frame.message.currentTarget
            held = environment.world.storageAt(target, slot)
            // Every scheme applies its refunds before the charge, which is the
            // ordering that was already here. It is unobservable either way: a
            // frame that cannot afford the charge halts, and a halted frame's
            // refunds are discarded rather than merged.
            // A PREFIX RATHER THAN A REPLACEMENT, and the scheme below is
            // untouched: EIP-2929 charges "an additional `COLD_SLOAD_COST`" for
            // a slot this transaction has not reached, over whatever the
            // metering scheme works out, and moves five of that scheme's figures
            // rather than any of its clauses. So the two are orthogonal here as
            // they are in the document -- a network can run either scheme under
            // either of these.
            firstReach = environment.rules.stateAccessMetering match
              case StateAccessMetering.Settled  => BigInt(0)
              case StateAccessMetering.WarmCold =>
                if frame.accessedStorageKeys.contains((target, slot)) then BigInt(0)
                else
                  frame.accessedStorageKeys = frame.accessedStorageKeys + ((target, slot))
                  schedule.coldStorageAccess
            settled = environment.rules.storageMetering match
              case StorageMetering.Legacy => legacyStorageCharge(frame, schedule, held, value)
              case StorageMetering.Net | StorageMetering.NetWithSentry =>
                netStorageCharge(frame, schedule, environment.world.committedStorageAt(target, slot), held, value)
            _ <- frame.charge(firstReach + settled)
            _ <- mayChangeState(frame)
          yield
            environment.world.setStorage(target, slot, value)
            advance(frame)
        )

      // ── The digest of a region of memory ───────────────────────────────────

      case Opcode.Keccak256 =>
        exceptional(
          for
            offset <- frame.stack.pop()
            size <- frame.stack.pop()
            settled = schedule.keccak256Base + schedule.keccak256PerWord * wholeWords(size)
            _ <- reach(frame, settled, (offset, size))
            digest = Keccak256.hash(regionOf(frame, offset, size).toIArray)
            _ <- frame.stack.push(Word.fromBytes(Bytes.fromIArray(digest.toBytes)))
          yield advance(frame)
        )

      // ── What the invocation emitted ────────────────────────────────────────

      // The topics sit below the region on the stack, so they are taken after
      // it, and the count comes from which operation this is rather than from
      // an operand.
      case emitting if emitting.code >= Opcode.Log0.code && emitting.code <= Opcode.Log4.code =>
        val topicCount = emitting.code - Opcode.Log0.code
        exceptional(
          for
            offset <- frame.stack.pop()
            size <- frame.stack.pop()
            topics <- takeTopics(frame, topicCount)
            settled = schedule.logBase + schedule.logDataPerByte * size.toBigInt +
              schedule.logTopic * topicCount
            _ <- reach(frame, settled, (offset, size))
            _ <- mayChangeState(frame)
          yield
            frame.logs = frame.logs :+ Log(frame.message.currentTarget, topics, regionOf(frame, offset, size))
            advance(frame)
        )

      // ── Ending this invocation with something to hand back ─────────────────

      // The program counter is left where it is. The specification says so
      // explicitly, and nothing reads it afterwards because the invocation has
      // ended -- but a counter moved past the end of the code would make a
      // resumed frame start somewhere it never reached.
      case Opcode.Return =>
        exceptional(
          for
            offset <- frame.stack.pop()
            size <- frame.stack.pop()
            _ <- reach(frame, schedule.zero, (offset, size))
          yield
            frame.output = regionOf(frame, offset, size)
            frame.running = false
        )

      // Everything `RETURN` does with memory, at the same price, ending the
      // invocation the other way: what it wrote is discarded, what it had not
      // spent stays with it, and its caller is told it failed. The proposal
      // states the equivalence itself -- "the semantics of `REVERT` with
      // respect to memory and memory cost are identical to those of `RETURN`"
      // (`ethereum/EIPs` @ `9e393a79`, `EIPS/eip-140.md`, Final).
      case Opcode.Revert =>
        val named =
          for
            offset <- frame.stack.pop()
            size <- frame.stack.pop()
            _ <- reach(frame, schedule.zero, (offset, size))
          yield frame.output = regionOf(frame, offset, size)
        named match
          case Left(halt) => Left(Fault.Exceptional(halt))
          case Right(())  => Left(Fault.Reverted)

      // ── Ending this invocation and giving its balance away ─────────────────

      // The registration is what the transaction acts on later; nothing is
      // removed here. Until the transaction ends the account still answers
      // reads and still runs when called, which is why registering and
      // destroying are separate acts in the specification too.
      case Opcode.SelfDestruct =>
        exceptional(
          for
            operand <- frame.stack.pop()
            beneficiary = addressOf(operand)
            originator = frame.message.currentTarget
            // THE REFUND IS EARNED BEFORE THE CHARGE IS PAID, which is the order
            // both authorities have. `src/ethereum/forks/tangerine_whistle/vm/instructions/system.py`
            // at `ccaaaba58` adds to the counter at :408 and charges at :410;
            // `ethereum/go-ethereum-pow` at `v1.10.26` adds the refund inside
            // `gasSelfdestruct` (`core/vm/gas_table.go:438`) and the interpreter
            // spends what that function returned afterwards. The line numbers
            // are the whole of the evidence here, so the ref is what keeps them
            // checkable.
            //
            // `SSTORE` above takes the same order against the same source, so
            // this is the machine agreeing with itself as well as with them.
            //
            // It reads like the wrong way round now the charge can fail, and
            // nothing here makes the difference observable, because three sites
            // outside this one hold that: [[run]] restores the world of a frame
            // that halted, [[incorporate]] takes up a frame's counters only
            // where it stopped, and whatever settles a transaction reads them
            // only where it succeeded. **The third of those is a test fixture
            // today** -- no transaction layer exists in this module yet -- so
            // the requirement it carries is one the real layer inherits rather
            // than one anything in production states.
            //
            // Conformance still decides the order: the two cannot be told apart
            // from outside, and the alternative is an unforced divergence in a
            // consensus path.
            _ = if !frame.alreadyRegistered(originator) then frame.refundCounter += schedule.refundSelfDestruct
            // The account paid out to is looked at before anything is charged,
            // which is the reason this operation cannot carry a settled price:
            // what it costs depends on the state its operand names, and under
            // one reading on the state of the account ending as well. At the
            // original specification both terms are nothing.
            //
            // WHAT THIS OPERATION MOVES IS THE WHOLE BALANCE OF THE ACCOUNT
            // ENDING, so that balance is what answers whether it moves value at
            // all, and it is read here rather than below because a price is
            // settled before it is paid.
            // THE ONE OPERATION THAT PAYS FOR A FIRST REACH AND NOTHING FOR A
            // REPEAT, where the four call forms pay the reduced figure for a
            // repeat. The document states the exception and its reason --
            // "`SELFDESTRUCT` does not charge a `WARM_STORAGE_READ_COST` in case
            // the recipient is already warm, which differs from how the other
            // call-variants work ... a `SELFDESTRUCT` already costs `5K` and is
            // a no-op if invoked more than once" (`ethereum/EIPs` @ `dbfa6bee8`,
            // `EIPS/eip-2929.md`, Final) -- so this is a surcharge added to what
            // the operation already pays rather than a figure replacing it, and
            // reusing the call family's helper here would overcharge every
            // destruction to an account this transaction had already reached.
            _ <- frame.charge(
              schedule.selfDestruct +
                firstReachSurcharge(frame, environment.rules, beneficiary) +
                newAccountSurcharge(
                  environment.rules,
                  environment.world,
                  beneficiary,
                  !environment.world.balanceOf(originator).isZero,
                  schedule.selfDestructNewAccount
                )
            )
            _ <- mayChangeState(frame)
          yield
            val world = environment.world
            // Both balances are read before either is written, so an account
            // naming itself as the beneficiary ends with nothing rather than
            // with twice what it had.
            val beneficiaryHeld = world.balanceOf(beneficiary)
            val originatorHeld = world.balanceOf(originator)
            world.setBalance(beneficiary, beneficiaryHeld.add(originatorHeld))
            world.setBalance(originator, Word.Zero)
            frame.accountsToDelete = frame.accountsToDelete + originator
            // The account paid out to is reached by this whatever it receives,
            // which the proposal names in its own list of the four ways an
            // account is left holding nothing.
            frame.touchedAccounts = frame.touchedAccounts + beneficiary
            frame.running = false
        )

      // ── Invocations this one starts ────────────────────────────────────────

      case Opcode.Create   => create(frame, environment, salted = false)
      case Opcode.Create2  => create(frame, environment, salted = true)
      case Opcode.Call     => messageCall(frame, environment, CallForm.ToTheAccountNamed)
      case Opcode.CallCode =>
        messageCall(frame, environment, CallForm.WithTheNamedAccountsCode)
      case Opcode.DelegateCall =>
        messageCall(frame, environment, CallForm.WithTheNamedAccountsCodeKeepingTheCaller)
      case Opcode.StaticCall =>
        messageCall(frame, environment, CallForm.ToTheAccountNamedWithoutChangingState)

      case unbuilt => Left(Fault.NotBuilt(unbuilt))

  /** Which account a message call runs as, whose code it runs, and whose
    * identity the invocation carries.
    *
    * ==Three axes, each arriving with a form==
    *
    * While there were two forms they differed in exactly one thing -- which
    * account runs -- and one sentence covered them. The third moves a second
    * axis: it keeps the caller and the value its own caller was invoked with, so
    * the code it runs cannot tell it was reached indirectly. The fourth moves a
    * third: it settles what the invocation it starts is allowed to do rather
    * than who it is. **That is what makes this an enumeration of forms rather
    * than a boolean**, and it is why the shared implementation reads the form at
    * five places rather than one.
    *
    * All four take the account to borrow code from off the stack. Two of them
    * take a value off it as well; of the other two, one inherits the value it
    * carries and one has none.
    */
  private enum CallForm:

    /** The account named on the stack runs, under its own storage. */
    case ToTheAccountNamed

    /** This account runs, under its own storage, using the named account's
      * code.
      */
    case WithTheNamedAccountsCode

    /** This account runs, under its own storage, using the named account's
      * code -- and the caller and value are the ones this frame was itself
      * invoked with rather than anything on the stack.
      *
      * Nothing moves: the value is carried for the code to read, not
      * transferred, so this form charges no surcharge for sending, forwards no
      * stipend, and cannot be refused for a balance it never spends.
      */
    case WithTheNamedAccountsCodeKeepingTheCaller

    /** The account named on the stack runs, under its own storage, and whatever
      * it starts is asked not to change state.
      *
      * It sends nothing, and sends nothing by construction rather than by the
      * caller's choice: no value comes off the stack, so the operation takes six
      * operands where the first two take seven. The transfer is still performed
      * -- of nothing -- which is what the specification does by passing
      * `should_transfer_value=True` beside `value=U256(0)`
      * (`ethereum/execution-specs` @ `20f7f6271a`,
      * `src/ethereum/forks/byzantium/vm/instructions/system.py`).
      */
    case ToTheAccountNamedWithoutChangingState

  /** Starts a nested invocation of another account's code.
    *
    * ==The price is settled before the destination is looked at==
    *
    * Everything the caller pays -- the settled part, the surcharge for bringing
    * the destination into being, the surcharge for sending anything, and the
    * whole of the gas actually forwarded -- is charged in one go, before any
    * balance is read. [[EvmRules.newAccountCharge]] is what decides when the
    * first of those two surcharges is levied at all, and the two conditions it
    * chooses between do not agree about what a call sending nothing pays. What
    * the callee receives is that forwarded gas plus a stipend where value was
    * sent, which comes out of the surcharge the caller already paid rather than
    * out of the caller's remaining gas.
    *
    * ==How much is forwarded is the fork's to say, and it is settled first==
    *
    * The caller asks for an amount; [[EvmRules.gasForwarded]] decides how much
    * of it the callee gets, out of what the caller would still hold once this
    * operation's own price and its memory were paid. That is why the memory cost
    * is worked out here rather than left to `reach` to fold in: the figure has
    * to exist before anything is taken. Where a chain caps nothing the answer
    * is the whole request, so a caller asking for more than it can cover runs
    * out of gas on the charge below rather than quietly getting less.
    *
    * ==What a failed nested invocation gives back depends on how it failed==
    *
    * A callee that halts returns no gas; one that reverts returns whatever it
    * had not spent, and its payload reaches both the caller's output area and
    * the caller's return-data buffer. Either way the caller learns of the
    * failure only from the zero this pushes. The two refusals that happen
    * before the callee starts -- too little balance, and too deeply nested --
    * are different again: they hand the forwarded gas straight back, because
    * nothing ran.
    */
  private def messageCall(
      frame: Frame,
      environment: Environment,
      form: CallForm
  ): Either[Fault, Unit] =
    val schedule = environment.schedule
    val world = environment.world
    // Bound once, because inheriting the caller's identity is not one difference
    // but five: no value comes off the stack, no surcharge is paid for sending
    // one, no stipend is forwarded, no balance can refuse the call, and NOTHING
    // MOVES. The specification reaches the same five by giving this form its own
    // entry point that charges a base and a request and nothing else, then hands
    // the shared path a flag saying not to move anything.
    val inherits = form == CallForm.WithTheNamedAccountsCodeKeepingTheCaller
    val forbidsChangingState = form == CallForm.ToTheAccountNamedWithoutChangingState
    val taken =
      for
        requested <- frame.stack.pop()
        named <- frame.stack.pop()
        // Written out per form rather than as a pair of flags, because this is
        // what settles how many operands the operation takes and a form added
        // without an answer here would silently read one belonging to something
        // else.
        value <- form match
          case CallForm.ToTheAccountNamed | CallForm.WithTheNamedAccountsCode => frame.stack.pop()
          case CallForm.WithTheNamedAccountsCodeKeepingTheCaller              => Right(frame.message.value)
          case CallForm.ToTheAccountNamedWithoutChangingState                 => Right(Word.Zero)
        inputOffset <- frame.stack.pop()
        inputSize <- frame.stack.pop()
        outputOffset <- frame.stack.pop()
        outputSize <- frame.stack.pop()
        codeAddress = addressOf(named)
        runsAs = form match
          case CallForm.ToTheAccountNamed                        => codeAddress
          case CallForm.ToTheAccountNamedWithoutChangingState    => codeAddress
          case CallForm.WithTheNamedAccountsCode                 => frame.message.currentTarget
          case CallForm.WithTheNamedAccountsCodeKeepingTheCaller => frame.message.currentTarget
        // Deliberately NOT `Message.transfersValue`, which this form also
        // decides and which is a weaker claim: that field says the invocation
        // performs the transfer at all, and is true of a call sending nothing.
        sends = !inherits && !value.isZero
        // THE FORM THAT SENDS NOTHING BY CONSTRUCTION CARRIES NO SURCHARGE
        // TERM AT ALL, which is not the same as carrying one that happens to be
        // nothing: its destination is the account it names and so can be
        // absent, and [[NewAccountCharge.WhenTheDestinationIsAbsent]] does not
        // read whether value moves. Both authorities omit the term rather than
        // zeroing it -- the specification's `staticcall` passes an `extra_gas`
        // of the base alone where `call` adds a `create_gas_cost`
        // (`ethereum/execution-specs` @ `20f7f6271a`,
        // `src/ethereum/forks/byzantium/vm/instructions/system.py`), and
        // `gasStaticCall` adds memory and the forwarded request where `gasCall`
        // adds `params.CallNewAccountGas` (`ethereum/go-ethereum-pow` @
        // `v1.10.26`, `core/vm/gas_table.go`).
        //
        // The two borrowing forms keep the term, whose scaladoc argues why a
        // chain never levies it on them. That argument does not reach this form,
        // because this one's destination is not the account already running.
        // THE ACCOUNT NAMED IS WHAT IS REACHED, in every one of the four forms:
        // two of them run as that account and two borrow its code, and both
        // authorities warm the operand either way. It is deliberately not
        // `runsAs`, which for the two borrowing forms is the account already
        // running and would leave the code being borrowed reached for free.
        //
        // WHERE THIS TERM SITS IS OBSERVABLE, and it sits where the settled base
        // sat: "the `100`/`2600` cost is applied immediately (exactly like how
        // `700` was charged before this EIP), i.e: before calculating the
        // `63/64ths` available for entering the call" (`ethereum/EIPs` @
        // `dbfa6bee8`, `EIPS/eip-2929.md`, Final). It is part of `ownPrice`,
        // which `spare` subtracts before the grant is worked out, so a cold call
        // forwards less than a warm one and both differ from the settled scheme.
        ownPrice = costOfReaching(frame, environment.rules, schedule.callBase, codeAddress) +
          (if forbidsChangingState then BigInt(0)
           else newAccountSurcharge(environment.rules, world, runsAs, sends, schedule.newAccount)) +
          (if sends then schedule.callValue else BigInt(0))
        memoryCost = expansionCost(frame, (inputOffset, inputSize), (outputOffset, outputSize))
        granted = environment.rules.gasForwarded
          .forward(spare(frame.gasLeft, ownPrice + memoryCost), requested.toBigInt)
        _ <- reach(frame, ownPrice + granted, (inputOffset, inputSize), (outputOffset, outputSize))
        // THE FORM IS TESTED AS WELL AS THE VALUE, because `sends` above is
        // true of the form that borrows another account's code and that form is
        // excluded by name -- "As an exception, CALLCODE is not considered
        // state-changing, even with a non-zero value" (`ethereum/EIPs` @
        // `9e393a79`, `EIPS/eip-214.md`, Final). Testing the value alone would
        // refuse a call the network permits.
        sendsToAnotherAccount = form == CallForm.ToTheAccountNamed && !value.isZero
        _ <- if sendsToAnotherAccount then mayChangeState(frame) else Right(())
      yield
        val forwarded = granted + (if sends then schedule.callStipend else BigInt(0))
        val input = regionOf(frame, inputOffset, inputSize)
        (codeAddress, runsAs, value, forwarded, input, outputOffset, outputSize)

    taken match
      case Left(halt) => Left(Fault.Exceptional(halt))
      case Right((codeAddress, runsAs, value, forwarded, input, outputOffset, outputSize)) =>
        // Cleared here so that a refusal below hands the caller an empty buffer
        // rather than whatever the invocation before this one left in it. The
        // specification clears it in the same place -- ahead of the depth check
        // in the shared path, and again in each form's own balance refusal.
        frame.returnData = Bytes.Empty
        if (!inherits && world.balanceOf(frame.message.currentTarget).toBigInt < value.toBigInt) ||
          frame.message.depth + 1 > Stack.Limit
        then
          frame.gasLeft += forwarded
          exceptional(frame.stack.push(Word.Zero).map(_ => advance(frame)))
        else
          val invoker = if inherits then frame.message.caller else frame.message.currentTarget
          val nested = new Frame(
            Message(
              caller = invoker,
              currentTarget = runsAs,
              codeAddress = Some(codeAddress),
              value = value,
              data = input,
              transfersValue = !inherits,
              // A STICKY OR: one form sets the flag and none clears it, so a
              // nesting under an invocation already asked not to change state
              // stays that way whichever form asked for it. The specification
              // writes the same expression --
              // `is_static=params.is_staticcall or evm.message.is_static`.
              isStatic = frame.message.isStatic || forbidsChangingState,
              depth = frame.message.depth + 1
            ),
            Code(world.codeOf(codeAddress)),
            forwarded,
            frame.registeredSoFar,
            frame.accessedAddresses,
            frame.accessedStorageKeys
          )
          run(nested, environment) match
            case Left(unsupported) => Left(Fault.NotBuilt(unsupported.opcode))
            case Right(outcome)    =>
              val answer = outcome match
                case Outcome.Stopped(gasLeft, output) =>
                  incorporate(frame, nested, gasLeft)
                  writeBack(frame, outputOffset, outputSize, output)
                  frame.returnData = output
                  Word.One
                // The payload reaches the output area as well as the buffer,
                // which the proposal states rather than leaves to be inferred:
                // it "will also be copied to the output area, i.e. it is
                // handled in the same way as the regular return data is
                // handled" (`ethereum/EIPs` @ `9e393a79`, `EIPS/eip-140.md`,
                // Final). The specification reaches it structurally, writing
                // the child's output back on both branches of one function.
                case Outcome.Reverted(gasLeft, output) =>
                  incorporateAfterFailure(frame, nested, environment.rules, gasLeft)
                  writeBack(frame, outputOffset, outputSize, output)
                  frame.returnData = output
                  Word.Zero
                case Outcome.Halted(_) =>
                  incorporateAfterFailure(frame, nested, environment.rules, BigInt(0))
                  frame.returnData = Bytes.Empty
                  Word.Zero
              exceptional(frame.stack.push(answer).map(_ => advance(frame)))

  /** Starts a nested invocation that deploys a new account's code.
    *
    * ==Three ways this ends without running anything, and they differ in gas==
    *
    * A creator that cannot cover the endowment, one whose transaction count has
    * no room to grow, and a nesting one level too deep all hand the forwarded
    * gas straight back. A destination that is not free to deploy over does not:
    * it consumes everything forwarded and still increments the creator's count,
    * which is the specification's own asymmetry rather than an oversight here.
    *
    * ==The address is settled before the code runs==
    *
    * It comes from the creator and the count it holds now, so the code being
    * deployed can read its own address and nothing can move it.
    */
  private def create(
      frame: Frame,
      environment: Environment,
      salted: Boolean
  ): Either[Fault, Unit] =
    val schedule = environment.schedule
    val world = environment.world
    val taken =
      for
        endowment <- frame.stack.pop()
        offset <- frame.stack.pop()
        size <- frame.stack.pop()
        // Fourth and last, which is where the specification pops it -- below the
        // region operands rather than above them. Popped before the charge, so
        // a creation that cannot afford itself has still consumed the same
        // operands as one that can.
        salt <- if salted then frame.stack.pop().map(Some(_)) else Right(None)
        // EIP-1014 charges the initialization code's hashing on top of the base,
        // at the per-word rate KECCAK256 itself uses, because the address
        // derivation hashes that code. CREATE derives from a count and hashes
        // nothing, so it pays nothing here.
        hashing = if salted then schedule.keccak256PerWord * wholeWords(size) else BigInt(0)
        _ <- reach(frame, schedule.createBase + hashing, (offset, size))
        // A creation asks for nothing, so what it may be given is decided
        // against everything the creator holds. Whatever the rules keep back
        // stays with the creator while the deployment runs, on top of whatever
        // the deployment does not spend.
        forwarded = environment.rules.gasForwarded.forward(frame.gasLeft, frame.gasLeft)
        // TAKEN BY CHARGING RATHER THAN BY ASSIGNMENT, which is [[Frame]]'s own
        // contract: gas leaves through `charge`, which refuses rather than going
        // negative, and a frame that overspent is indistinguishable afterwards
        // from one that could afford it. Both rules this build ships return no
        // more than what remains, so nothing is refused here today -- it is
        // [[GasForwarding]]'s postcondition enforced rather than assumed, at the
        // one site where nothing else would catch a breach. The sibling site
        // needs no such guard: it charges the grant together with the
        // operation's own price a line later, so an over-large grant is
        // unaffordable there by construction.
        _ <- frame.charge(forwarded)
        _ <- mayChangeState(frame)
      yield (endowment, regionOf(frame, offset, size), forwarded, salt)

    taken match
      case Left(halt)                                    => Left(Fault.Exceptional(halt))
      case Right((endowment, initCode, forwarded, salt)) =>
        // Cleared before any of the three refusals below, which is where the
        // specification clears it: none of them instantiates a frame, so none
        // of them may leave the previous invocation's payload readable.
        frame.returnData = Bytes.Empty
        val creator = frame.message.currentTarget
        val count = world.nonceOf(creator)
        // THE COUNT IS STILL READ AND STILL INCREMENTED BELOW on both paths.
        // What the salted form changes is only what the address is derived
        // FROM -- so a salted creation consumes the creator's next ordinary
        // address exactly as an unsalted one does, which is the specification's
        // behavior and not an artifact of sharing this code.
        val target = salt match
          case Some(value) => ContractAddress.create2(creator, value, initCode)
          case None        => ContractAddress.of(creator, count)
        val cannotBeAttempted =
          world.balanceOf(creator).toBigInt < endowment.toBigInt ||
            count == UInt64.MaxValue ||
            frame.message.depth + 1 > Stack.Limit
        // REACHED BY THE CREATOR AND NOT BY THE CREATION, which is what makes a
        // failed deployment leave its own address warm: the write is to this
        // frame's set, and the child built below is handed a copy of it.
        // "Immediately (ie. before checks are done to determine whether or not
        // the address is unclaimed) add the address being created to
        // `accessed_addresses`, but gas costs of `CREATE` and `CREATE2` are
        // unchanged" (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2929.md`, Final)
        // -- so nothing is charged here, and where it sits is the whole rule.
        //
        // BELOW the three refusals above and ABOVE the deployability check,
        // which is where `ethereum/execution-specs` @ `20f7f6271` puts it
        // (`forks/berlin/vm/instructions/system.py:91`). The document's "before
        // checks are done to determine whether or not the address is unclaimed"
        // names that one check and not these three: a creation refused for a
        // balance, a count or a depth pushes zero without its address ever being
        // reached, and warming one there would differ from every client.
        if !cannotBeAttempted && environment.rules.stateAccessMetering == StateAccessMetering.WarmCold then
          frame.accessedAddresses = frame.accessedAddresses + target
        if cannotBeAttempted then
          frame.gasLeft += forwarded
          exceptional(frame.stack.push(Word.Zero).map(_ => advance(frame)))
        else if !deployableAt(world, target) then
          // The count still rises. A destination that is not free to deploy
          // over consumes the creator's next address as surely as one that is.
          world.setNonce(creator, UInt64.fromBits(count.toBits + 1))
          exceptional(frame.stack.push(Word.Zero).map(_ => advance(frame)))
        else
          world.setNonce(creator, UInt64.fromBits(count.toBits + 1))
          val nested = new Frame(
            // The flag is INHERITED rather than written `false`, though a
            // creation from an invocation asked not to change state is refused
            // above and so cannot reach here. One rule at both nesting sites
            // rather than two, and it stays right if a later proposal ever
            // admits a creation from one.
            Message(
              caller = creator,
              currentTarget = target,
              codeAddress = None,
              value = endowment,
              data = Bytes.Empty,
              transfersValue = true,
              isStatic = frame.message.isStatic,
              depth = frame.message.depth + 1
            ),
            Code(initCode),
            forwarded,
            frame.registeredSoFar,
            frame.accessedAddresses,
            frame.accessedStorageKeys
          )
          deploy(nested, environment) match
            case Left(unsupported) => Left(Fault.NotBuilt(unsupported.opcode))
            case Right(outcome)    =>
              val answer = outcome match
                // A DEPLOYMENT THAT SUCCEEDED LEAVES THE BUFFER EMPTY, which
                // is the one place the buffer does not carry what the child
                // handed back. The proposal names it as an exception in its own
                // words -- "`CREATE` and `CREATE2` are considered to return the
                // empty buffer in the success case and the failure data in the
                // failure case" (`ethereum/EIPs` @ `9e393a79`,
                // `EIPS/eip-211.md`, Final) -- and it is what keeps a new
                // account's code out of its creator's reach as return data.
                case Outcome.Stopped(gasLeft, _) =>
                  incorporate(frame, nested, gasLeft)
                  frame.returnData = Bytes.Empty
                  wordOf(target)
                // No write-back, because a creation names no output area: the
                // payload is readable only through the buffer.
                case Outcome.Reverted(gasLeft, output) =>
                  incorporateAfterFailure(frame, nested, environment.rules, gasLeft)
                  frame.returnData = output
                  Word.Zero
                case Outcome.Halted(_) =>
                  incorporateAfterFailure(frame, nested, environment.rules, BigInt(0))
                  frame.returnData = Bytes.Empty
                  Word.Zero
              exceptional(frame.stack.push(answer).map(_ => advance(frame)))

  /** Runs a deployment and stores whatever it returned as the new account's
    * code.
    *
    * ==Whether failing to pay for the code is a failure is the fork's to say==
    *
    * Before EIP-2 it is not. A deployment that returns more code than its
    * remaining gas can pay to store still SUCCEEDS: it keeps that gas, deploys
    * nothing, and the creating operation is told the address as though code had
    * been stored. An account left behind with no code is the visible
    * consequence, and it is the correct one there. EIP-2 reverses it -- the
    * creation is undone, every remaining unit of gas is taken, and the creating
    * operation is told it failed -- and `codeDepositMustSucceed` is which of
    * the two this chain runs.
    *
    * ==The bound on deployed code is checked BEFORE that charge, and the
    * ordering is a decision==
    *
    * EIP-170 states the rule and not where it sits: *"if contract creation
    * initialization returns data with length of more than `MAX_CODE_SIZE`
    * bytes, contract creation fails with an out of gas error"*
    * (`ethereum/EIPs` @ `4a79c79ab`, `EIPS/eip-170.md`, Final). So the
    * implementations differ, and three of them say in a comment why they check
    * first: an over-long deployment must not be charged for storing code it
    * will never store. `besu-eth/besu` @ `c2addd9424` --
    * *"Oversized contracts must fail without charging code deposit gas or state
    * gas. We must check this first"*; `bluealloy/revm` @ `3064c0901c` --
    * *"This must be checked BEFORE charging state gas for code deposit"*;
    * `ethereum/go-ethereum` @ `6bb0588ad8` on its newest branch --
    * *"Check max code size BEFORE charging gas so over-max code does not consume
    * state gas"*. `ethereum/go-ethereum-pow` @ `v1.10.26` and
    * `ethereumclassic/core-geth` @ `4185df450` check first without saying why.
    * The executable specification charges first
    * (`forks/spurious_dragon/vm/interpreter.py` at `ccaaaba58`), and so does
    * go-ethereum's own pre-Amsterdam branch.
    *
    * **Both orderings are observationally identical wherever both rules are
    * live**, which is every network that has this one, since EIP-2 precedes
    * EIP-170 everywhere: an over-long deployment ends with the state restored,
    * no gas left and the creating operation told nothing either way, whether the
    * charge ran first and succeeded, ran first and failed, or never ran. So the
    * ordering is not chosen on the outcome. It is chosen because checking first
    * keeps the bound's failure out of `codeDepositMustSucceed`'s reach BY
    * CONSTRUCTION rather than by a guard -- a bound is not a price, and a
    * network that has not adopted EIP-2 must not thereby soften one.
    *
    * That separation is what the field agrees on where its orderings disagree:
    * both go-ethereum lines raise a distinct error the pre-EIP-2 leniency
    * explicitly excludes, and besu runs its rule outside the branch that reads
    * `requireCodeDepositToSucceed` at all. `NethermindEth/nethermind` @
    * `c35ce1b1ab` is the exception -- it prices an over-long deployment at
    * `ulong.MaxValue` in `CodeDepositHandler`, which folds the bound into the
    * charge and so hands it to that flag.
    *
    * **The failure is an ordinary out-of-gas and earns no member of [[Halt]]**,
    * which is the specification's answer as well as the proposal's wording: the
    * two forks' `vm/exceptions.py` are byte-identical at `ccaaaba58`, and the
    * over-long case raises the `OutOfGasError` already there. The clients that
    * name it apart do so to say which check failed, and [[Halt]] records why
    * this machine has nothing to say with that.
    *
    * ==A reserved leading byte is refused ahead of both, and it does earn one==
    *
    * [[reservesPrefix]] runs before the bound and before the charge, which is
    * nearest to the specification's own order -- it compares the leading byte,
    * then charges, then bounds the length. The bound sits where this file
    * already put it, so the two orderings are unchanged relative to each other
    * and only the new check is placed.
    *
    * **The clients read here take a different order, and the field is split
    * three ways rather than agreeing.** `ethereum/go-ethereum-pow` @ `v1.10.26`
    * `core/vm/evm.go:456-462` bounds the length first and compares the byte
    * second; `besu-eth/besu` @ `fdf1247c6d` builds its rules as
    * `List.of(MaxCodeSizeRule.from(evm), PrefixCodeRule.of())` and takes the
    * first that fails, so it does the same. So the orders are: the
    * specification's byte-charge-bound, those two clients' bound-byte-charge,
    * and this file's byte-bound-charge.
    *
    * **That three-way split costs nothing, which is the actual point** -- see
    * below. It is spelled out because an earlier wording here claimed this was
    * the order every client takes, and both clients it cites two paragraphs
    * above contradict it.
    *
    * **Nothing observable turns on where it goes**, for the reason stated above:
    * all three refusals end with the state restored, no gas left and the
    * creating operation told nothing. It is placed to match the specification
    * because there is no reason to differ, not because a case would fail.
    *
    * Unlike the bound, this reason IS a member of [[Halt]] -- the forks either
    * side of the proposal that introduces it declare different exception sets,
    * where the forks either side of EIP-170 declare the same. That type's own
    * note carries the sweep and both of its calibrations.
    *
    * ==The second snapshot earns its place once that flag can be set==
    *
    * It used to be omitted, on the reasoning that the outer one restores
    * whatever this one would have. **That reasoning was fork-specific and this
    * is the fork that ends it.** [[run]] releases its snapshot the moment it
    * returns a stop, and a deposit that cannot be paid for is a stop -- so by
    * the time the charge fails there is nothing left holding the deployment's
    * writes, and the reversal would have had nothing to reverse.
    *
    * The specification takes its snapshot at the same place, and the nonce the
    * creator spent is outside it in both: it is incremented by the operation
    * that asked for the creation, before this runs, so a failed deposit does not
    * hand it back.
    *
    * ==The count the created account starts with is written HERE, and the
    * outer snapshot is what undoes it==
    *
    * [[EvmRules.createdAccountNonce]] is written before the initialization code
    * runs, which is where the proposal that raises it puts it -- *"prior to the
    * execution of the initialisation code"* -- and where three implementations
    * put it: the executable specification calls `increment_nonce` between the
    * two snapshots in `process_create_message`
    * (`forks/spurious_dragon/vm/interpreter.py` at `ccaaaba58`),
    * `ethereum/go-ethereum-pow` @ `v1.10.26` writes it in `EVM.create` just
    * after taking its one snapshot, and `besu-eth/besu` @ `c2addd9424` writes it
    * in `ContractCreationProcessor.start`.
    *
    * **So a deployment whose own execution halted has to be undone from here
    * rather than from [[run]]**, whose snapshot is taken after this write and
    * cannot reach it. The specification restores its outer snapshot on exactly
    * that branch, and go-ethereum reverts to the single snapshot it took before
    * the write.
    *
    * **That is true at a count of zero as well, and the reasoning that it is not
    * is the trap.** A write of zero changes no field, but the write itself
    * brings the account into being -- [[WorldState]] states that every writing
    * member does -- so a network that raises nothing still leaves an account
    * behind at a halted deployment's address unless the reversal runs. The
    * write is therefore unconditional, which is `besu-eth/besu` @ `c2addd9424`'s
    * own shape: its `ContractCreationProcessor` writes the value it was
    * configured with at every fork, including the zero its earliest definitions
    * pass.
    *
    * ==Two callers, and the second one is outside this module==
    *
    * Visible rather than internal to this file, because deployment has two
    * entry points and only one of them is an operation. `CREATE` is the first;
    * a transaction whose recipient is absent is the second, and the layer that
    * settles such a transaction sits above this one. The specification treats
    * them as one path for the same reason -- its `process_message_call`
    * dispatches on an empty target and both arms reach the same creation -- and
    * go-ethereum publishes its `Create` so the state transition can call what
    * the operation calls. Copying this into that layer would put a fork-varying
    * rule in two places.
    */
  def deploy(
      nested: Frame,
      environment: Environment
  ): Either[Unsupported, Outcome] =
    val schedule = environment.schedule
    val world = environment.world
    val rules = environment.rules
    val taken = world.snapshot()

    // Three ways a deployment is undone, and they undo the same three things. A
    // second copy of this could drift from the first without any test naming
    // both.
    def undone(halt: Halt): Outcome =
      world.restore(taken)
      nested.gasLeft = BigInt(0)
      Outcome.Halted(halt)

    world.setNonce(nested.message.currentTarget, rules.createdAccountNonce)

    run(nested, environment) match
      // An entry this build cannot run is undone here as well, for the reason
      // [[run]] undoes it: it is not an outcome a chain reaches, and a
      // half-written world left behind would read as one.
      case Left(unsupported) =>
        world.restore(taken)
        Left(unsupported)
      case Right(Outcome.Stopped(_, code)) =>
        if reservesPrefix(rules, code) then Right(undone(Halt.InvalidContractPrefix))
        else if rules.maxCodeSize.exists(bound => code.length > bound) then Right(undone(Halt.OutOfGas))
        else
          nested.charge(schedule.codeDepositPerByte * BigInt(code.length)) match
            case Left(halt) if rules.codeDepositMustSucceed => Right(undone(halt))
            case Left(_)                                    =>
              nested.output = Bytes.Empty
              Right(Outcome.Stopped(nested.gasLeft, Bytes.Empty))
            case Right(()) =>
              world.setCode(nested.message.currentTarget, code)
              Right(Outcome.Stopped(nested.gasLeft, code))
      // Undone like a halt and emptied like neither: `undone` would take the gas
      // the revert kept and discard the payload the proposal makes readable, so
      // the reversal is written out rather than shared. The specification runs
      // the same restore for both, its own branch turning on whether an error
      // was recorded and not on which kind
      // (`ethereum/execution-specs` @ `20f7f6271a`,
      // `src/ethereum/forks/byzantium/vm/interpreter.py:178,194-195`).
      case Right(Outcome.Reverted(gasLeft, output)) =>
        world.restore(taken)
        Right(Outcome.Reverted(gasLeft, output))
      case Right(Outcome.Halted(halt)) => Right(undone(halt))

  /** Whether a creation may deploy at `address`.
    *
    * **This is settled. It was surveyed across six implementations, decided by
    * the operator, and then measured against three synced chains. Do not
    * relitigate it from the two-client reading that used to be recorded here.**
    *
    * ==The situation==
    *
    * An address can hold storage while having no code and a zero transaction
    * count. Nothing in normal operation produces that; it is a historical
    * residue from before EIP-161. When a creation targets such an address, the
    * implementations do three different things.
    *
    * ==The field, surveyed==
    *
    *   - **Refuse** -- the executable specification at `ccaaaba58`
    *     (`account_deployable` in `frontier/state_tracker.py`); besu at
    *     `c2addd9424` (`ContractCreationProcessor.java:124`, which ors
    *     `!account.isStorageEmpty()` into the predicate); besu-etc at
    *     `eb4248c997`; ethrex at `2367dc810` (`account.rs:114`, whose own
    *     comment says the flag exists for exactly this collision).
    *   - **Proceed** -- go-ethereum at `6bb0588ad` and core-geth at
    *     `4185df450`, both testing the count and code hash only.
    *   - **Clear, then proceed** -- nethermind at `c35ce1b1ab`
    *     (`EvmInstructions.Create.cs:210`), wiping a dead account's storage
    *     first.
    *
    * go-ethereum's `isEIP7610RejectedAccount` is **an enumerated per-chain
    * address set, not a rule** -- its own comment says chains absent from the
    * set are assumed to have no rejected accounts. That is viable only because
    * the affected set cannot grow, which is the next point.
    *
    * ==Why the set cannot grow==
    *
    * EIP-161 closes it twice over: `spurious_dragon`'s `process_create_message`
    * calls `destroy_storage` and then `increment_nonce` on the target, so any
    * residue is wiped at creation and the new account can never again present a
    * zero count. Both halves are here -- the count is
    * [[EvmRules.createdAccountNonce]], written by [[deploy]], and the wipe is
    * this refusal standing in for it. So on a network raising that count the
    * affected addresses are those that acquired storage before it did, and there
    * will be no more of them.
    *
    * ==What decided it: the three behaviors were measured against ETC mainnet==
    *
    * besu, nethermind and core-geth -- one from each of the three groups above
    * -- have each synced ETC mainnet, peered to one another, and each imported
    * block 24,884,770 with the hash
    * `0x8887a9cd60877acc48331b8fcae2d3efc9644c7a6d54275dd839047c0d01b90c`. A
    * block hash commits to the state root, so three implementations resolving
    * this case three different ways computed the same state root, with no
    * consensus error in any of them. **The divergence has never fired on ETC.**
    *
    * That is what makes refusing safe here rather than merely defensible: it
    * would have produced core-geth's own state root for the whole of that
    * history. It does not establish that no residue address exists -- only that
    * nothing has targeted one in a way that mattered.
    *
    * ==So: refuse==
    *
    * Operator decision, following besu. It is also the specification's reading,
    * which matters because the corpus this layer is certified against is
    * generated from it, and it is the safer direction, since the alternative
    * deploys over storage that is still present.
    *
    * ==Reversing this is not a one-line change==
    *
    * The refusal is what makes the specification's `destroy_storage` in the
    * create path a provable no-op, which is why no trie-level storage wipe
    * exists. **A reversal needs that operation built first.**
    *
    * ==The published corpus was consulted, and it CONFIRMS this==
    *
    * The reversing trigger below is not merely unfired. **The fixture it names
    * exists and expects the opposite** -- that is, it expects the refusal.
    * `state_tests/for_frontier/paris/eip7610_create_collision/initcollision/`
    * ships 18 cases, every one `fork_Frontier`, from a specification test whose
    * reference is `EIPS/eip-7610.md` and which is marked `valid_from("Frontier")`
    * (its `CREATE2` variant is separately gated to Constantinople, so the
    * CREATE-opcode form genuinely runs from this fork). Its `non-empty-balance`
    * parametrization is exactly the residue shape -- zero count, no code, a
    * storage slot set -- and it expects the account byte-identical afterwards
    * with the sender charged the whole gas limit.
    *
    * **The nine CREATE-opcode cases execute here and agree.** So this is not a
    * defensible reading of a split field; it is the answer the published corpus
    * requires at this fork.
    *
    * ==A SECOND published corpus expects the opposite, and the discriminator is
    * its filling client's vintage==
    *
    * *"The published corpus"* above is not the only one, and a reader who meets
    * this rule through a failing fixture rather than through this comment will
    * meet the other one first. `etclabscore/tests-etc` @ `06ec708ea7` carries
    * `stSStoreTest/InitCollision` and `stExtCodeHash/dynamicAccountOverwriteEmpty`
    * at this network's labels, and **five of their entries expect the deployment
    * to succeed** -- the answer this rule refuses.
    *
    * **That corpus was filled by a client build predating this rule**, and
    * `ethereum/legacytests`, which carries the same two scenarios refilled by a
    * later one, agrees with this rule and is certified clean in
    * `org.fukuii.chainspec.certification.CertificationCorpora`. So the two
    * published corpora disagree with each other, and what separates them is when
    * each was filled rather than which network it is labelled for.
    *
    * **Neither the disagreement nor its direction is a reversing trigger**, for
    * the reason the next paragraph gives: a fixture older than the rule cannot
    * be evidence about it.
    * `org.fukuii.chainspec.certification.ClassicPublishedPhoenixStateCertificationSpec`
    * names those five entries and certifies them as known divergences, so they
    * are asserted rather than merely tolerated -- and a change in this rule's
    * answer moves that list.
    *
    * Reversing trigger, accordingly narrowed: **ECIP-1121 settling the question
    * the other way for the proof-of-work family**, in which case this becomes
    * configuration rather than a constant -- which the root-plus-deltas seam
    * already admits. A fixture reversal is no longer a live trigger, the
    * published fixture having landed on this side.
    */
  def deployableAt(world: WorldState, address: Address): Boolean =
    world.nonceOf(address) == UInt64.Zero && world.codeOf(address).isEmpty && !world.hasStorage(address)

  /** Whether `address` is DEAD: either this state holds no account there, or it
    * holds one that has nothing.
    *
    * ==Read the sibling above before reusing either, because they read alike==
    *
    * [[deployableAt]] is a collision rule -- no count, no code, and **no
    * storage**. This is EIP-161's account condition -- no count, no code, and
    * **no balance**. They share two terms and differ in two, so either one
    * standing in for the other compiles, agrees on most addresses, and is wrong
    * on exactly the ones each was written for. **Storage is deliberately absent
    * here**: the proposal defines *empty* as *"no code and zero nonce and zero
    * balance"* and says nothing about it (`ethereum/EIPs` @ `96523ef4d`,
    * `EIPS/eip-161.md`, Final).
    *
    * ==Dead and empty coincide here, and that is the seam's doing rather than
    * the proposal's==
    *
    * The proposal defines *dead* as *"either it is non-existent or it is
    * empty"*, which reads as two conditions to test. [[WorldState]] answers
    * every read for an absent account with the empty account's value, so an
    * absent account already satisfies all three terms and the first condition is
    * subsumed by the second. `ethereum/go-ethereum-pow` @ `v1.10.26` reaches the
    * same place and writes it as one function: `StateDB.Empty`, whose own
    * documentation is *"whether the state object is either non-existent or empty
    * according to the EIP161 specification"*.
    */
  def deadAt(world: WorldState, address: Address): Boolean =
    world.nonceOf(address) == UInt64.Zero && world.codeOf(address).isEmpty && world.balanceOf(address).isZero

  /** What reaching `address` costs under a warm-and-cold scheme, recording that
    * it has now been reached.
    *
    * ==The record is written before the charge is paid, and the order is the
    * specification's==
    *
    * `ethereum/execution-specs` @ `20f7f6271` adds to the set and then calls
    * `charge_gas` in every one of the operations that reach an account
    * (`forks/berlin/vm/instructions/environment.py:69-73` is the shortest of
    * them). Nothing observable rests on it -- an invocation that cannot pay
    * halts, and a halted invocation's set is discarded with everything else it
    * accumulated -- so this is the two agreeing rather than a rule either could
    * state alone.
    *
    * **Only the warm-and-cold scheme reaches this.** Under the settled scheme
    * the sets are never read, so nothing calls it and the reach goes unrecorded.
    */
  private def warmingAccount(frame: Frame, schedule: GasSchedule, address: Address): BigInt =
    if frame.accessedAddresses.contains(address) then schedule.warmAccess
    else
      frame.accessedAddresses = frame.accessedAddresses + address
      schedule.coldAccountAccess

  /** What reaching `address` costs under whichever scheme is in force, where the
    * operation has a settled figure to fall back on rather than a table entry.
    *
    * `EXTCODECOPY` and the four call forms work out their own price under both
    * schemes, so neither can express the difference in its entry and each names
    * the figure it would otherwise have used. The warm-and-cold scheme REPLACES
    * that figure rather than adding to it, which is what both authorities do:
    * `ethereum/execution-specs` @ `20f7f6271` substitutes `access_gas_cost` for
    * `GasCosts.OPCODE_CALL_BASE` in each call form's `extra_gas`
    * (`forks/berlin/vm/instructions/system.py`), leaving the surcharge and the
    * transfer terms beside it untouched.
    */
  private def costOfReaching(frame: Frame, rules: EvmRules, settled: BigInt, address: Address): BigInt =
    rules.stateAccessMetering match
      case StateAccessMetering.Settled  => settled
      case StateAccessMetering.WarmCold => warmingAccount(frame, rules.schedule, address)

  /** What a first reach at `address` adds to an operation that pays for a repeat
    * reach separately, or nothing.
    *
    * The shape `SELFDESTRUCT` alone takes, and it is not
    * [[costOfReaching]] with a zero settled figure: that would charge the
    * reduced figure for a repeat, where this charges nothing at all. See the
    * quotation at the site.
    */
  private def firstReachSurcharge(frame: Frame, rules: EvmRules, address: Address): BigInt =
    rules.stateAccessMetering match
      case StateAccessMetering.Settled  => BigInt(0)
      case StateAccessMetering.WarmCold =>
        if frame.accessedAddresses.contains(address) then BigInt(0)
        else
          frame.accessedAddresses = frame.accessedAddresses + address
          rules.schedule.coldAccountAccess

  /** The same for one account's storage slot, which is keyed by the pair. */
  private def warmingSlot(frame: Frame, schedule: GasSchedule, address: Address, slot: Word): BigInt =
    if frame.accessedStorageKeys.contains((address, slot)) then schedule.warmAccess
    else
      frame.accessedStorageKeys = frame.accessedStorageKeys + ((address, slot))
      schedule.coldStorageAccess

  /** Runs `body` over the account an operation's one operand names, at what
    * reaching it costs.
    *
    * ==The operand is popped here because the charge depends on it==
    *
    * [[priced]] settles a charge before the operation is entered, which a
    * warm-and-cold scheme cannot do: what reaching an account costs is decided
    * by which account, and the operand is where that is stated. So this pops
    * first and hands the body both the address and the figure, rather than the
    * figure alone.
    *
    * ==The two schemes reach the charge by different routes and the table says
    * which==
    *
    * Under [[StateAccessMetering.Settled]] the figure is the entry's, exactly as
    * [[priced]] reads it, and an entry that computes its own price is the same
    * mismatch [[priced]] reports. Under [[StateAccessMetering.WarmCold]] the
    * entry holds no figure and none is read, so a configuration whose entries
    * were never rebuilt still charges correctly -- which is why the rule and not
    * the entry is what selects the scheme.
    */
  private def reachingAnAccount(frame: Frame, operation: Operation, rules: EvmRules)(
      body: (Address, BigInt) => Either[Halt, Unit]
  ): Either[Fault, Unit] =
    frame.stack.pop() match
      case Left(halt)     => Left(Fault.Exceptional(halt))
      case Right(operand) =>
        val address = addressOf(operand)
        rules.stateAccessMetering match
          case StateAccessMetering.WarmCold =>
            exceptional(body(address, warmingAccount(frame, rules.schedule, address)))
          case StateAccessMetering.Settled =>
            operation.cost match
              case Cost.Fixed(gas) => exceptional(body(address, gas))
              case Cost.Computed   => Left(Fault.NotBuilt(operation.opcode))

  /** Runs `body` over the slot an operation's one operand names, at what
    * reaching it costs.
    *
    * [[reachingAnAccount]]'s counterpart, and its scaladoc carries the reasoning
    * both share. What differs is only the key: a slot is reached under the
    * account the invocation is running as, and the pair is what the set holds.
    */
  private def reachingASlot(frame: Frame, operation: Operation, rules: EvmRules)(
      body: (Word, BigInt) => Either[Halt, Unit]
  ): Either[Fault, Unit] =
    frame.stack.pop() match
      case Left(halt)  => Left(Fault.Exceptional(halt))
      case Right(slot) =>
        rules.stateAccessMetering match
          case StateAccessMetering.WarmCold =>
            exceptional(body(slot, warmingSlot(frame, rules.schedule, frame.message.currentTarget, slot)))
          case StateAccessMetering.Settled =>
            operation.cost match
              case Cost.Fixed(gas) => exceptional(body(slot, gas))
              case Cost.Computed   => Left(Fault.NotBuilt(operation.opcode))

  /** What bringing an operation's destination into being adds to its price.
    *
    * ==One home for a branch two operations take==
    *
    * `CALL` and `SELFDESTRUCT` levy this at figures a schedule names separately,
    * and the CONDITION is the one thing the two share: the proposal that changes
    * it changes it for both at once. A copy at each site could be moved at one
    * and left at the other with nothing naming both, which is a divergence worth
    * one indirection to make unwritable.
    *
    * ==`valueMoves` is the operation's answer and not this function's==
    *
    * What counts as moving value differs between the two -- a call reads its
    * operand, a destruction reads the whole balance of the account ending -- so
    * each site answers it and neither can be answered here.
    *
    * ==Which destinations can be dead, and why a borrowing call's cannot==
    *
    * A call's destination is the account it names, which may be in any state. A
    * borrowing call's is the account already running, and on a chain that one is
    * never dead. Every frame is built at one of four sites and each leaves its
    * account alive: a transaction's own invocation and an ordinary nested call
    * both take their code out of state, so an account executing anything holds
    * code; a borrowing call inherits the account its caller was running as, and
    * so inherits that; and a deployment's account holds the count
    * [[EvmRules.createdAccountNonce]] gave it before its code ran. The two
    * authorities reach the same place by giving those forms no surcharge term at
    * all -- the specification's `callcode` and `delegatecall` charge a base and a
    * transfer term and nothing else, and `ethereum/go-ethereum-pow` @ `v1.10.26`
    * adds none in `gasCallCode` or `gasDelegateCall`.
    *
    * **The deployment half of that rests on two rules arriving together, which
    * is a property of the field rather than of this record.** A network levying
    * this on a dead destination while leaving that count at zero would make an
    * account created with no endowment dead while its own initialization code
    * ran, and a borrowing call from it that sent something would pay a surcharge
    * neither authority charges. Neither of the two clients read here separates
    * them: `ethereumclassic/core-geth` @ `4185df450` gates both on one
    * transition and `ethereum/go-ethereum-pow` @ `v1.10.26` on one fork test. A
    * configuration that did is the first thing to look at if this ever fires for
    * a borrowing form.
    */
  private def newAccountSurcharge(
      rules: EvmRules,
      world: WorldState,
      destination: Address,
      valueMoves: Boolean,
      charge: BigInt
  ): BigInt =
    val levied = rules.newAccountCharge match
      case NewAccountCharge.WhenTheDestinationIsAbsent       => !world.accountExists(destination)
      case NewAccountCharge.WhenValueReachesADeadDestination => valueMoves && deadAt(world, destination)
    if levied then charge else BigInt(0)

  /** Takes what a nested invocation earned into the invocation that started it.
    *
    * Only a nested invocation that ended normally reaches this. One that halted
    * has nothing to give: its gas is gone, and its logs, its refunds and its
    * registrations are discarded along with the state it wrote.
    * [[incorporateAfterFailure]] is the other half of that rule, and the two are
    * written apart for the reason the specification writes two functions.
    */
  private def incorporate(frame: Frame, nested: Frame, gasLeft: BigInt): Unit =
    frame.gasLeft += gasLeft
    frame.logs = frame.logs ++ nested.logs
    frame.refundCounter += nested.refundCounter
    frame.accountsToDelete = frame.accountsToDelete | nested.accountsToDelete
    frame.touchedAccounts = frame.touchedAccounts | nested.touchedAccounts
    frame.accessedAddresses = frame.accessedAddresses | nested.accessedAddresses
    frame.accessedStorageKeys = frame.accessedStorageKeys | nested.accessedStorageKeys

  /** Takes up what a nested invocation that failed still gives its caller:
    * whatever gas it did not spend, and a reach at an address whose reaches are
    * not undone.
    *
    * ==The gas is an argument rather than a branch, because the specification
    * has one function here and not two==
    *
    * Its `incorporate_child_on_error` ends `evm.gas_left += child_evm.gas_left`
    * (`ethereum/execution-specs` @ `20f7f6271a`,
    * `src/ethereum/forks/byzantium/vm/__init__.py:196`), which is nothing for
    * an invocation that halted and the remainder for one that reverted. The
    * difference is what the child left, never a rule about which failure it
    * was, so a caller passes the figure and this adds it unconditionally.
    *
    * ==Discarding everything else is the absence of a call, and this is why
    * there is a call at all==
    *
    * [[incorporate]] is not reached from a failed invocation, which is what
    * discards its logs, its refunds and its registrations. A reach is
    * the one accumulator with an exception, so it is the one that needs a
    * counterpart rather than a silence. Where [[EvmRules.touchSurvivesFailure]]
    * is empty -- every network at every height before the proposal that names
    * an address -- this is the same silence written out.
    *
    * **[[Frame.accessedAddresses]] and [[Frame.accessedStorageKeys]] are
    * accumulators with NO exception**, so their line here is their absence:
    * *"if a scope reverts, the access lists should be in the state they were in
    * before that scope was entered"* (`ethereum/EIPs` @ `dbfa6bee8`,
    * `EIPS/eip-2929.md`, Final), and the caller's own copy is exactly that
    * state. The address a failed creation was to have been deployed at survives
    * anyway, because the creator warmed it in its own set before the child
    * existed -- so that exception needs nothing here either.
    *
    * ==One intersection covers what the specification writes as two arms, and
    * is WIDER than the two of them==
    *
    * Its `incorporate_child_on_error` re-adds the exempt address when the
    * child's own set holds it, and again when the child's own account IS that
    * address. Here the second is already the first: [[run]] records the account
    * an invocation runs as into that invocation's own set, so a call that
    * reached the address at all reached it through the set.
    *
    * **That second arm carries a guard this one does not** -- that the account
    * exists and is empty -- so what this leaves in the set is a strict superset
    * of what the specification leaves in its own. The two settle at the same
    * state only because the single site that consumes the set asks that same
    * question again before destroying anything, and
    * `org.fukuii.execution.TransactionProcessor.account` is where it is asked.
    * Its existence check is that guard rather than a defensive one.
    */
  private def incorporateAfterFailure(frame: Frame, nested: Frame, rules: EvmRules, gasLeft: BigInt): Unit =
    frame.gasLeft += gasLeft
    frame.touchedAccounts = frame.touchedAccounts | (nested.touchedAccounts & rules.touchSurvivesFailure)

  /** Copies as much of what a nested invocation returned as the caller made
    * room for, and no more.
    *
    * The caller names the room when it makes the call and has already paid to
    * hold it, so a longer answer is truncated rather than refused and a shorter
    * one leaves the rest of that room as it was.
    */
  private def writeBack(frame: Frame, offset: Word, size: Word, output: Bytes): Unit =
    val room = if size.toBigInt > BigInt(output.length) then output.length else size.toBigInt.toInt
    if room > 0 then frame.memory.write(startOf(offset, size), Bytes.fromIArray(output.toIArray.take(room)))

  /** Takes `count` topics off the stack, in the order the operation lists them.
    */
  private def takeTopics(frame: Frame, count: Int): Either[Halt, Vector[Hash]] =
    var taken: Either[Halt, Vector[Hash]] = Right(Vector.empty)
    var remaining = count
    while remaining > 0 && taken.isRight do
      taken = for
        soFar <- taken
        topic <- frame.stack.pop()
      yield soFar :+ Hash.fromBytesTruncating(topic.toBytes.toIArray)
      remaining -= 1
    taken

  /** How many whole words `size` bytes occupy, which is the unit the digest and
    * the copying operations are priced in.
    */
  private def wholeWords(size: Word): BigInt = (size.toBigInt + Word.Width - 1) / Word.Width

  /** The bytes of one region of memory, read after it has been paid for. */
  private def regionOf(frame: Frame, offset: Word, size: Word): Bytes =
    if size.isZero then Bytes.Empty else frame.memory.read(startOf(offset, size), size.toBigInt.toInt)

  /** Where a region begins, as an index.
    *
    * A region of no bytes begins nowhere and is never read, so its offset is
    * not narrowed -- an empty region at an offset no memory could reach is
    * legitimate and costs nothing, and narrowing it would be arithmetic on a
    * number that does not fit.
    */
  private def startOf(offset: Word, size: Word): Int =
    if size.isZero then 0 else offset.toBigInt.toInt

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

  /** Nothing, or the refusal a store owes an invocation with too little gas
    * left to be worth entering.
    *
    * ==Its own step, before the operands are read for anything else==
    *
    * EIP-2200 § *Specification* puts it first: *"If gasleft is less than or
    * equal to gas stipend, fail the current call frame with 'out of gas'
    * exception"* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-2200.md`, Final).
    * `ethereum/execution-specs` @ `20f7f6271a` places it after the two pops and
    * before everything else in `forks/istanbul/vm/instructions/storage.py`,
    * which is where the caller puts it.
    *
    * **The ordering is observable and is not a detail.** Both charging helpers
    * move [[Frame.refundCounter]] as they go, so a sentry applied after one of
    * them would leave a refund earned by a store that never happened.
    *
    * ==The threshold is the schedule's, not a constant here==
    *
    * [[GasSchedule.callStipend]] is the amount the document names -- *"the gas
    * stipend given to 'transfer'/'send'"* -- rather than a figure of its own,
    * and a network sets it. Reading it here is what keeps this refusal free of
    * a number the machine would own.
    */
  private def storageSentry(
      frame: Frame,
      schedule: GasSchedule,
      metering: StorageMetering
  ): Either[Halt, Unit] =
    metering match
      case StorageMetering.NetWithSentry if frame.gasLeft <= schedule.callStipend => Left(Halt.OutOfGas)
      case _                                                                      => Right(())

  /** The charge for a store under the scheme that reads only what the slot
    * holds now, applying its one refund on the way.
    *
    * Setting a slot that held nothing is the expensive case; every other
    * combination, including clearing one, is the cheaper one.
    */
  private def legacyStorageCharge(frame: Frame, schedule: GasSchedule, held: Word, value: Word): BigInt =
    if value.isZero && !held.isZero then frame.refundCounter += schedule.refundStorageClear
    if held.isZero && !value.isZero then schedule.storageSet else schedule.storageReset

  /** The charge for a store under EIP-1283's net metering, applying its refunds
    * on the way.
    *
    * ==Three cases, named by the document==
    *
    * *No-op* when the slot already holds what is being written. *Fresh* when it
    * has not been touched in this transaction, which is what `original ==
    * held` means. *Dirty* otherwise -- and the dirty case applies BOTH of its
    * clauses, which is the document's own wording and not an optimization to
    * collapse: a store can both cancel an earlier clear's refund and earn a
    * reset refund in one operation.
    *
    * ==The counter is DECREMENTED here, and that is the document's own design
    * for this codebase's shape==
    *
    * *"If an implementation uses 'execution-frame level' refund counter ...
    * then the refund counter needs to be changed to signed -- at internal
    * calls, a child refund can go below zero"* (`ethereum/EIPs` @ `dbfa6bee`,
    * `EIPS/eip-1283.md`, Final). [[Frame.refundCounter]] is exactly that shape,
    * so a negative value here is correct rather than a fault to guard against.
    * The guarantee the document does make is at TRANSACTION level, and that is
    * where it is worth checking.
    *
    * **`ethereum/go-ethereum` @ `e9e35a42f8` panics on a negative counter**
    * (`core/state/statedb.go:317-319`) and is right to, because its counter is
    * transaction-level and unsigned. Transplanting that assertion here would
    * raise on a valid execution.
    */
  private def netStorageCharge(
      frame: Frame,
      schedule: GasSchedule,
      original: Word,
      held: Word,
      value: Word
  ): BigInt =
    if held == value then schedule.netStorageNoop
    else if original == held then
      if original.isZero then schedule.netStorageInit
      else
        if value.isZero then frame.refundCounter += schedule.refundNetStorageClear
        schedule.netStorageClean
    else
      if !original.isZero then
        if held.isZero then frame.refundCounter -= schedule.refundNetStorageClear
        else if value.isZero then frame.refundCounter += schedule.refundNetStorageClear
      if original == value then
        if original.isZero then frame.refundCounter += schedule.refundNetStorageResetFromZero
        else frame.refundCounter += schedule.refundNetStorageReset
      schedule.netStorageDirty

  /** Nothing, or the refusal every state-changing operation owes an invocation
    * that was asked not to change state.
    *
    * ==Five arms over nine operations, and the price is paid at all of them==
    *
    * A store, an emission at any of the five topic counts, a creation, a
    * destruction, and a call that sends something. The proposal names exactly
    * those, with two exclusions worth keeping in view: `CALLCODE` is *"not
    * considered state-changing, even with a non-zero value"* and the borrowing
    * form that keeps its caller is not on its list at all (`ethereum/EIPs` @
    * `9e393a79`, `EIPS/eip-214.md`, Final).
    *
    * **Every arm sits after the operation's price is charged**, which is where
    * both authorities put it: the specification's five raises each follow
    * `charge_gas` (`ethereum/execution-specs` @ `20f7f6271a`,
    * `src/ethereum/forks/byzantium/vm/instructions/` -- `storage.py:80`,
    * `log.py:70`, `system.py:79`, `:298`, `:436`), and
    * `ethereum/go-ethereum-pow` @ `v1.10.26` charges an operation's constant and
    * dynamic gas in the interpreter before calling it at all. Two of the five
    * earn a refund earlier still and lose it, because the frame is dropped.
    *
    * One helper rather than five copies: the condition is one proposal's, and a
    * copy at each site could be moved at one and left at the others.
    */
  private def mayChangeState(frame: Frame): Either[Halt, Unit] =
    if frame.message.isStatic then Left(Halt.WriteInStaticContext) else Right(())

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

  /** Refuses a read reaching past the end of the buffer it reads from.
    *
    * ==The sum is taken before anything is narrowed, and that is the whole of
    * the difficulty==
    *
    * Both operands are full width, so a sum taken in a machine word wraps and
    * admits a read the network refuses. The specification widens both to
    * unbounded integers before comparing
    * (`ethereum/execution-specs` @ `20f7f6271a`,
    * `src/ethereum/forks/byzantium/vm/instructions/environment.py:440`), and
    * `ethereum/go-ethereum-pow` @ `v1.10.26` adds them in 256 bits and refuses
    * on the narrowing rather than after it
    * (`core/vm/instructions.go:329-338`).
    *
    * ==A read of nothing is still checked==
    *
    * The proposal states the boundary in both directions: "reading 0 bytes
    * from the end of the buffer will read 0 bytes; reading 0 bytes from
    * one-byte out of the buffer causes an exception"
    * (`ethereum/EIPs` @ `9e393a79`, `EIPS/eip-211.md`, Final). So this is asked
    * whatever the size, and only the copy that follows it is skipped when
    * there is nothing to copy.
    */
  private def withinReturnData(frame: Frame, start: Word, size: Word): Either[Halt, Unit] =
    if start.toBigInt + size.toBigInt > BigInt(frame.returnData.length) then Left(Halt.OutOfBoundsRead)
    else Right(())

  /** `size` bytes of `source` from `start`, both already known to be inside it.
    *
    * Narrowing is safe only because of that: [[withinReturnData]] has refused
    * anything reaching past the end, so both operands fit the index the source
    * is held at.
    */
  private def sliceOf(source: Bytes, start: Word, size: Word): Bytes =
    val from = start.toBigInt.toInt
    Bytes.fromIArray(source.toIArray.slice(from, from + size.toBigInt.toInt))

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
    */
  private def expand(frame: Frame, base: BigInt, offset: Word, size: BigInt): Either[Halt, Int] =
    val extent = Word(size)
    reach(frame, base, (offset, extent)).map(_ => startOf(offset, extent))

  /** Charges `settled` plus what it costs to hold every one of `regions`, and
    * grows memory far enough for all of them.
    *
    * ==A region of no bytes costs nothing, whatever offset it names==
    *
    * The specification skips an empty region outright rather than measuring it,
    * so a zero-length read at an offset no memory could reach is affordable.
    * Charging it would make an operation that touches nothing unaffordable.
    *
    * ==Several regions cost what the furthest of them costs==
    *
    * The specification charges them one at a time, each against the size the
    * one before it left, which sums to the cost of reaching the furthest and
    * nothing for the rest. Taking the furthest directly is that sum, and is
    * independent of the order the regions are given in -- which the operations
    * with two of them rely on, since the answer is written at an offset the
    * caller chose and may sit either side of the input.
    *
    * ==The bound is not a policy==
    *
    * Memory is indexed by `Int`, and reaching past that costs more gas than any
    * schedule can express, so it is refused as unaffordable rather than as too
    * large.
    */
  private def reach(frame: Frame, settled: BigInt, regions: (Word, Word)*): Either[Halt, Unit] =
    val furthest = furthestOf(regions)
    for
      _ <- frame.charge(settled + GasCost.expansion(BigInt(frame.memory.size), furthest))
      _ <- if furthest > MaxReach then Left(Halt.OutOfGas) else Right(())
    yield frame.memory.ensure(furthest.toInt)

  /** The furthest byte any of `regions` addresses, which is what reaching all of
    * them costs -- see [[reach]] for why the furthest alone is the whole sum.
    */
  private def furthestOf(regions: Seq[(Word, Word)]): BigInt =
    regions.filterNot(_._2.isZero).map(region => region._1.toBigInt + region._2.toBigInt).foldLeft(BigInt(0))(_ max _)

  /** What extending memory to hold `regions` costs, worked out without charging
    * it.
    *
    * [[reach]] settles a price and takes it in one act, which is what every
    * operation but one family wants. A nested invocation is the exception: how
    * much gas it may be given is decided against what the caller would still
    * hold once this is paid, so the figure has to exist before anything is
    * taken.
    */
  private def expansionCost(frame: Frame, regions: (Word, Word)*): BigInt =
    GasCost.expansion(BigInt(frame.memory.size), furthestOf(regions))

  /** What is left of `held` once `cost` is paid, and nothing where it does not
    * cover it.
    *
    * This is [[GasForwarding]]'s non-negative contract met at the one site that
    * has to meet it -- the creating site passes a frame's own remaining gas,
    * which cannot be negative, and needs no clamp. A frame that cannot cover the price it is about to be
    * charged still owes the rule a figure, and it runs out of gas on that charge
    * a moment later whatever the rule answered -- so the clamp lives here, once,
    * rather than inside every rule that could be written.
    */
  private def spare(held: BigInt, cost: BigInt): BigInt = if held <= cost then BigInt(0) else held - cost

  /** The furthest byte an operation may address before it is refused.
    *
    * The margin is not caution: it is what makes the `toInt` below safe.
    * [[Memory.ensure]] rounds its argument up to the next whole word, adding as
    * much as `Word.Width - 1`, and that addition is on `Int`. Bounding the reach
    * a whole word below `Int.MaxValue` leaves exactly enough headroom for the
    * rounding to land inside the range instead of wrapping negative.
    *
    * So subtracting `Word.Width` here is a precondition of the rounding, not a
    * round number chosen for comfort, and raising this value re-opens an
    * overflow the gas charge above would never reach on its own.
    */
  private val MaxReach: BigInt = BigInt(Int.MaxValue - Word.Width)
