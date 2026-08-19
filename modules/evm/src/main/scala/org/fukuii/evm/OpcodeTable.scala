package org.fukuii.evm

/** How an operation's charge is arrived at.
  *
  * Two shapes rather than one number, because an operation whose price depends
  * on its operands has no number to record. Writing a plausible one anyway is
  * the failure this type exists to make impossible: a figure standing where a
  * computation belongs reads exactly like a figure that was checked.
  */
enum Cost:

  /** The whole charge, settled before the operation runs and depending on
    * nothing but which operation it is.
    */
  case Fixed(gas: BigInt)

  /** The operation works out what it costs from its operands, and charges
    * before it acts.
    *
    * Repricing one of these is a change to the [[GasSchedule]] rather than to
    * the table, because the table holds no number to change. Both are values a
    * chain configuration produces, so the seam is the same one; which of the
    * two carries a given price is decided by whether that price is settled
    * before the operation runs.
    */
  case Computed

/** An operation as a table holds it: which one, and what it costs. */
final case class Operation(opcode: Opcode, cost: Cost)

/** The operations a chain runs, and the prices it runs them at.
  *
  * ==A table is a value, and a fork is a change to one==
  *
  * A network is not a branch in the interpreter. It is a table, and the
  * difference between two networks -- or between two forks of one network -- is
  * which entries the table has and what they cost. Nothing here asks which fork
  * is active, because by the time a table exists that question has been
  * answered.
  *
  * The field settled this rather than this project inventing it. go-ethereum
  * builds each fork by applying per-proposal mutators to the one before it, and
  * core-geth -- which serves several networks from one binary, as this project
  * intends to -- drops the named forks from the code entirely: it starts from
  * `newBaseInstructionSet()` and applies a flat sequence of per-proposal
  * activations, with the fork names surviving only as comments.
  *
  * ==Changes come in three kinds, and only two need a combinator==
  *
  * Adding an operation, removing one, and repricing one. [[adding]] covers the
  * first and the third, because inserting an entry for an operation already
  * present replaces it -- which is how core-geth expresses a repricing, in
  * place, against an entry that is already there. [[removing]] covers the
  * second, and it is not hypothetical: `scroll-tech/go-ethereum` takes
  * `SELFDESTRUCT` out of its table, recording that the operation then behaves
  * exactly as an undefined byte does. That is what removal means here too, and
  * it falls out of absence rather than needing a state of its own.
  */
final class OpcodeTable private (private val entries: Map[Int, Operation]):

  /** The operation a byte runs, or nothing where this chain runs none.
    *
    * Nothing distinguishes a byte that was never an operation from one this
    * chain removed, and that is the specification's own answer: both are
    * invalid, and an interpreter meeting either halts the same way.
    */
  def operationAt(code: Int): Option[Operation] = entries.get(code)

  def contains(opcode: Opcode): Boolean = entries.get(opcode.code).exists(_.opcode == opcode)

  def opcodes: Set[Opcode] = entries.values.map(_.opcode).toSet

  def size: Int = entries.size

  /** The table with `operation` in it, replacing whatever held its byte. */
  def adding(operation: Operation): OpcodeTable =
    new OpcodeTable(entries.updated(operation.opcode.code, operation))

  /** The table without `opcode`, whose byte then runs nothing. */
  def removing(opcode: Opcode): OpcodeTable = new OpcodeTable(entries.removed(opcode.code))

object OpcodeTable:

  /** The operations the machine started with, priced by `schedule`.
    *
    * Named for what it is rather than for the fork that shipped it, following
    * the client that had the same problem: core-geth calls this
    * `newBaseInstructionSet` and leaves the fork's name in a comment, because a
    * baseline shared by every network it serves cannot carry one network's name
    * for it.
    */
  def baseline(schedule: GasSchedule): OpcodeTable =
    val fixed: Map[Opcode, BigInt] =
      Map(
        Opcode.Stop -> schedule.zero,
        Opcode.SelfDestruct -> schedule.zero,
        Opcode.Add -> schedule.veryLow,
        Opcode.Sub -> schedule.veryLow,
        Opcode.Lt -> schedule.veryLow,
        Opcode.Gt -> schedule.veryLow,
        Opcode.SLt -> schedule.veryLow,
        Opcode.SGt -> schedule.veryLow,
        Opcode.Eq -> schedule.veryLow,
        Opcode.IsZero -> schedule.veryLow,
        Opcode.And -> schedule.veryLow,
        Opcode.Or -> schedule.veryLow,
        Opcode.Xor -> schedule.veryLow,
        Opcode.Not -> schedule.veryLow,
        Opcode.Byte -> schedule.veryLow,
        Opcode.CallDataLoad -> schedule.veryLow,
        Opcode.Mul -> schedule.low,
        Opcode.Div -> schedule.low,
        Opcode.SDiv -> schedule.low,
        Opcode.Mod -> schedule.low,
        Opcode.SMod -> schedule.low,
        Opcode.SignExtend -> schedule.low,
        Opcode.AddMod -> schedule.mid,
        Opcode.MulMod -> schedule.mid,
        Opcode.Jump -> schedule.mid,
        Opcode.JumpI -> schedule.high,
        Opcode.JumpDest -> schedule.jumpDest,
        Opcode.BlockHash -> schedule.blockHash,
        Opcode.Balance -> schedule.balance,
        Opcode.ExtCodeSize -> schedule.externalBase,
        Opcode.SLoad -> schedule.storageLoad,
        Opcode.Address -> schedule.base,
        Opcode.Origin -> schedule.base,
        Opcode.Caller -> schedule.base,
        Opcode.CallValue -> schedule.base,
        Opcode.CallDataSize -> schedule.base,
        Opcode.CodeSize -> schedule.base,
        Opcode.GasPrice -> schedule.base,
        Opcode.Coinbase -> schedule.base,
        Opcode.Timestamp -> schedule.base,
        Opcode.Number -> schedule.base,
        Opcode.Difficulty -> schedule.base,
        Opcode.GasLimit -> schedule.base,
        Opcode.Pop -> schedule.base,
        Opcode.MSize -> schedule.base,
        Opcode.Pc -> schedule.base,
        Opcode.Gas -> schedule.base
      ) ++ stackFamilies.map(_ -> schedule.veryLow)

    val computed: Set[Opcode] = Opcode.values.toSet -- fixed.keySet
    val all =
      fixed.map((opcode, gas) => Operation(opcode, Cost.Fixed(gas))) ++
        computed.map(opcode => Operation(opcode, Cost.Computed))
    new OpcodeTable(all.map(operation => operation.opcode.code -> operation).toMap)

  /** The three families whose members differ only in a count, and which are
    * priced alike across all of them.
    */
  private def stackFamilies: Set[Opcode] =
    Opcode.values.toSet.filter { opcode =>
      Opcode.isPush(opcode) ||
      (opcode.code >= Opcode.Dup1.code && opcode.code <= Opcode.Swap16.code)
    }
