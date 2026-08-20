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

  /** Runs one invocation: gives it the account it runs as, moves any value it
    * carries, executes its code, and undoes all of that if it does not end
    * normally.
    *
    * ==The undo is the reason this is not just the loop==
    *
    * At this fork an invocation that halts leaves no trace, and that has to be
    * true of the outermost invocation as much as of a nested one -- the
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
      world.touch(frame.message.currentTarget)
      transfer(world, frame.message)
      val result = frame.message.codeAddress.flatMap(precompiles.at) match
        case Some(precompile) => Right(runNatively(frame, precompile))
        case None             => execute(frame, environment)
      result match
        case Right(Outcome.Stopped(_, _)) => ()
        case _                            => world.restore(taken)
      result

  /** Charges for a precompile and runs it, or halts because it cannot be paid
    * for.
    *
    * The charge comes first, so an invocation that cannot afford the answer
    * never computes it. A shortfall is an ordinary exceptional halt and keeps
    * nothing, which is the same rule [[execute]] applies to an operation that
    * cannot pay.
    */
  private def runNatively(frame: Frame, precompile: Precompile): Outcome =
    frame.charge(precompile.gasFor(frame.message.data)) match
      case Left(halt) =>
        frame.gasLeft = BigInt(0)
        Outcome.Halted(halt)
      case Right(()) =>
        frame.output = precompile.run(frame.message.data)
        Outcome.Stopped(frame.gasLeft, frame.output)

  /** Moves an invocation's value from its caller to the account it runs as.
    *
    * The caller's balance was checked by whichever operation asked for the
    * invocation, so a shortfall here is a caller that did not check rather than
    * a state a chain can reach -- and the machine's word wraps, so an unchecked
    * subtraction would turn a shortfall into an enormous balance rather than
    * into a failure.
    */
  private def transfer(world: JournaledWorldState, message: Message): Unit =
    if !message.value.isZero then
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

      // ── Ending this invocation and giving its balance away ─────────────────

      // The registration is what the transaction acts on later; nothing is
      // removed here. Until the transaction ends the account still answers
      // reads and still runs when called, which is why registering and
      // destroying are separate acts in the specification too.
      case Opcode.SelfDestruct =>
        priced(operation) { gas =>
          for
            operand <- frame.stack.pop()
            beneficiary = addressOf(operand)
            originator = frame.message.currentTarget
            _ = if !frame.alreadyRegistered(originator) then frame.refundCounter += schedule.refundSelfDestruct
            _ <- frame.charge(gas)
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
            frame.running = false
        }

      // ── Invocations this one starts ────────────────────────────────────────

      case Opcode.Create   => create(frame, environment)
      case Opcode.Call     => messageCall(frame, environment, CallForm.ToTheAccountNamed)
      case Opcode.CallCode =>
        messageCall(frame, environment, CallForm.WithTheNamedAccountsCode)

      case unbuilt => Left(Fault.NotBuilt(unbuilt))

  /** Which account a message call runs as, and whose code it runs.
    *
    * The two forms differ in exactly this and nothing else, so they share one
    * implementation and the difference is a value rather than a duplicated
    * body. Both take the account to borrow code from off the stack.
    */
  private enum CallForm:

    /** The account named on the stack runs, under its own storage. */
    case ToTheAccountNamed

    /** This account runs, under its own storage, using the named account's
      * code.
      */
    case WithTheNamedAccountsCode

  /** Starts a nested invocation of another account's code.
    *
    * ==The price is settled before the destination is looked at==
    *
    * Everything the caller pays -- the settled part, the surcharge for an
    * account this state has never held, the surcharge for sending anything, and
    * the whole of the gas being forwarded -- is charged in one go, before any
    * balance is read. What the callee receives is that forwarded gas plus a
    * stipend where value was sent, which comes out of the surcharge the caller
    * already paid rather than out of the caller's remaining gas.
    *
    * ==A nested invocation that fails costs the caller everything it forwarded==
    *
    * There is no cheap failure at this fork, so a callee that halts returns no
    * gas and the caller learns of it only from the zero this pushes. The two
    * refusals that happen before the callee starts -- too little balance, and
    * too deeply nested -- are different: they hand the forwarded gas straight
    * back, because nothing ran.
    */
  private def messageCall(
      frame: Frame,
      environment: Environment,
      form: CallForm
  ): Either[Fault, Unit] =
    val schedule = environment.schedule
    val world = environment.world
    val taken =
      for
        requested <- frame.stack.pop()
        named <- frame.stack.pop()
        value <- frame.stack.pop()
        inputOffset <- frame.stack.pop()
        inputSize <- frame.stack.pop()
        outputOffset <- frame.stack.pop()
        outputSize <- frame.stack.pop()
        codeAddress = addressOf(named)
        runsAs = form match
          case CallForm.ToTheAccountNamed        => codeAddress
          case CallForm.WithTheNamedAccountsCode => frame.message.currentTarget
        settled = schedule.callBase + requested.toBigInt +
          (if world.accountExists(runsAs) then BigInt(0) else schedule.newAccount) +
          (if value.isZero then BigInt(0) else schedule.callValue)
        _ <- reach(frame, settled, (inputOffset, inputSize), (outputOffset, outputSize))
      yield
        val forwarded = requested.toBigInt + (if value.isZero then BigInt(0) else schedule.callStipend)
        val input = regionOf(frame, inputOffset, inputSize)
        (codeAddress, runsAs, value, forwarded, input, outputOffset, outputSize)

    taken match
      case Left(halt) => Left(Fault.Exceptional(halt))
      case Right((codeAddress, runsAs, value, forwarded, input, outputOffset, outputSize)) =>
        if world.balanceOf(frame.message.currentTarget).toBigInt < value.toBigInt ||
          frame.message.depth + 1 > Stack.Limit
        then
          frame.gasLeft += forwarded
          exceptional(frame.stack.push(Word.Zero).map(_ => advance(frame)))
        else
          val nested = new Frame(
            Message(frame.message.currentTarget, runsAs, Some(codeAddress), value, input, frame.message.depth + 1),
            Code(world.codeOf(codeAddress)),
            forwarded,
            frame.registeredSoFar
          )
          run(nested, environment) match
            case Left(unsupported) => Left(Fault.NotBuilt(unsupported.opcode))
            case Right(outcome)    =>
              val answer = outcome match
                case Outcome.Stopped(gasLeft, output) =>
                  incorporate(frame, nested, gasLeft)
                  writeBack(frame, outputOffset, outputSize, output)
                  Word.One
                case Outcome.Halted(_) => Word.Zero
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
      environment: Environment
  ): Either[Fault, Unit] =
    val schedule = environment.schedule
    val world = environment.world
    val taken =
      for
        endowment <- frame.stack.pop()
        offset <- frame.stack.pop()
        size <- frame.stack.pop()
        _ <- reach(frame, schedule.createBase, (offset, size))
      yield (endowment, regionOf(frame, offset, size))

    taken match
      case Left(halt)                   => Left(Fault.Exceptional(halt))
      case Right((endowment, initCode)) =>
        // Everything left is forwarded, so the creator holds nothing while the
        // deployment runs and is given back only what the deployment did not
        // spend.
        val forwarded = frame.gasLeft
        frame.gasLeft = BigInt(0)
        val creator = frame.message.currentTarget
        val count = world.nonceOf(creator)
        val target = ContractAddress.of(creator, count)
        if world.balanceOf(creator).toBigInt < endowment.toBigInt ||
          count == UInt64.MaxValue ||
          frame.message.depth + 1 > Stack.Limit
        then
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
            Message(creator, target, None, endowment, Bytes.Empty, frame.message.depth + 1),
            Code(initCode),
            forwarded,
            frame.registeredSoFar
          )
          deploy(nested, environment) match
            case Left(unsupported) => Left(Fault.NotBuilt(unsupported.opcode))
            case Right(outcome)    =>
              val answer = outcome match
                case Outcome.Stopped(gasLeft, _) =>
                  incorporate(frame, nested, gasLeft)
                  wordOf(target)
                case Outcome.Halted(_) => Word.Zero
              exceptional(frame.stack.push(answer).map(_ => advance(frame)))

  /** Runs a deployment and stores whatever it returned as the new account's
    * code.
    *
    * ==Failing to pay for the code is not a failure at this fork==
    *
    * A deployment that returns more code than its remaining gas can pay to store
    * still SUCCEEDS: it keeps that gas, deploys nothing, and the creating
    * operation is told the address as though code had been stored. Both sources
    * are explicit about it -- the specification catches the charge and empties
    * the output rather than recording an error, and go-ethereum carries the same
    * behavior behind a check for the later proposal that reverses it. An
    * account left behind with no code is the visible consequence, and it is the
    * correct one here.
    *
    * ==No second snapshot is taken here, and that is a property rather than an
    * omission==
    *
    * The specification wraps this path in its own snapshot as well, with a
    * storage clear between the two. That clear cannot do anything under the
    * rule applied by [[deployableAt]], which refuses to deploy over an account
    * holding storage at all -- so nothing happens between the two snapshots and
    * the outer one restores exactly what the inner one already did. The code
    * stored below sits outside both, which is what keeps a successful
    * deployment's code from being undone.
    *
    * ==Two callers, one of them not built yet==
    *
    * Visible within this module rather than to this file, because deployment
    * has two entry points and only one of them is an operation. `CREATE` is the
    * first; a transaction whose recipient is absent is the second, and the
    * layer that settles such a transaction does not exist here yet. The
    * specification treats them as one path for the same reason -- its
    * `process_message_call` dispatches on an empty target and both arms reach
    * the same creation -- and go-ethereum publishes its `Create` so the state
    * transition can call what the operation calls. Copying this into whatever
    * stands in for that layer would put a fork-varying rule in two places.
    */
  private[evm] def deploy(
      nested: Frame,
      environment: Environment
  ): Either[Unsupported, Outcome] =
    val schedule = environment.schedule
    run(nested, environment).map {
      case Outcome.Stopped(_, code) =>
        nested.charge(schedule.codeDepositPerByte * BigInt(code.length)) match
          case Left(_) =>
            nested.output = Bytes.Empty
            Outcome.Stopped(nested.gasLeft, Bytes.Empty)
          case Right(()) =>
            environment.world.setCode(nested.message.currentTarget, code)
            Outcome.Stopped(nested.gasLeft, code)
      case halted => halted
    }

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
    * zero count. This fork lacks only the `increment_nonce`; the
    * `destroy_storage` call is already in `frontier`'s own create path, whose
    * comment calls the case "highly unlikely". So the affected addresses are
    * those that acquired storage before Spurious Dragon, and there will be no
    * more of them.
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
    * Reversing trigger, accordingly narrowed: **ECIP-1121 settling the question
    * the other way for the proof-of-work family**, in which case this becomes
    * configuration rather than a constant -- which the baseline-plus-deltas seam
    * already admits. A fixture reversal is no longer a live trigger, the
    * published fixture having landed on this side.
    */
  private[evm] def deployableAt(world: WorldState, address: Address): Boolean =
    world.nonceOf(address) == UInt64.Zero && world.codeOf(address).isEmpty && !world.hasStorage(address)

  /** Takes what a nested invocation earned into the invocation that started it.
    *
    * Only a nested invocation that ended normally reaches this. One that halted
    * has nothing to give: its gas is gone, and its logs, its refunds and its
    * registrations are discarded along with the state it wrote.
    */
  private def incorporate(frame: Frame, nested: Frame, gasLeft: BigInt): Unit =
    frame.gasLeft += gasLeft
    frame.logs = frame.logs ++ nested.logs
    frame.refundCounter += nested.refundCounter
    frame.accountsToDelete = frame.accountsToDelete | nested.accountsToDelete

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
    val furthest =
      regions.filterNot(_._2.isZero).map(region => region._1.toBigInt + region._2.toBigInt).foldLeft(BigInt(0))(_ max _)
    for
      _ <- frame.charge(settled + GasCost.expansion(BigInt(frame.memory.size), furthest))
      _ <- if furthest > MaxReach then Left(Halt.OutOfGas) else Right(())
    yield frame.memory.ensure(furthest.toInt)

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
