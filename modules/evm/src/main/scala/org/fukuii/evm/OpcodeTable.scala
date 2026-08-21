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

  /** Whether two tables run the same operations at the same prices.
    *
    * Total, and nothing can defeat it: an entry is an [[Operation]], which is a
    * case class over an enum and a [[Cost]] carrying a `BigInt`, so every level
    * of this comparison is already by value.
    *
    * ==Two networks running the same rules is a question with an answer==
    *
    * [[ChainRules]] is compared as a whole to establish it, and a record
    * answers by value only if every member does. This was one of the two that
    * did not: two identical tables built separately compared unequal, so
    * whether the answer came out right depended on whether a caller had built
    * one value or two.
    *
    * ==A delta leaving a table ALONE is still asserted with `eq`, deliberately==
    *
    * The seam's own claim is that a proposal touching no operation leaves the
    * same table rather than an equal copy, and only reference identity says
    * that. Adding equality here does not weaken those assertions because they
    * never used it; it makes their use of `eq` a choice a reader can see rather
    * than the only thing available.
    */
  override def equals(other: Any): Boolean = other match
    case that: OpcodeTable => entries == that.entries
    case _                 => false

  override def hashCode: Int = entries.hashCode

object OpcodeTable:

  /** The operations the EVM was originally specified with, priced by
    * `schedule`.
    *
    * ==The SHAPE is the machine's; the PRICES are a network's, and that is the
    * parameter==
    *
    * Which byte runs which operation, and whether an operation's charge is
    * settled before it runs or worked out from its operands, is the EVM's own
    * definition. What each of them costs is not: every network sets those, and
    * a network that reprices one does so through a proposal. So this holds the
    * first and takes the second as an argument, which is the seam between the
    * machine and a chain configuration drawn through one function.
    *
    * ==This is the ROOT of every network's derivation, not any network's
    * choice==
    *
    * Every EVM-equivalent network's operation set is this plus the proposals it
    * adopts: Polygon, Gnosis and the OP Stack all run `DELEGATECALL` because
    * they adopt EIP-7, and none of them disagrees about where it came from.
    * What IS a network's choice is stopping here -- running this with no
    * proposal applied -- and that statement lives with that network's
    * configuration rather than in this module.
    *
    * **So moving this out of the machine would make every future network either
    * duplicate it or import the first network's copy**, which is the shape this
    * project is trying not to have.
    *
    * ==Named for what it is, which is what the field does too==
    *
    * Four production clients keep this in the machine and parameterize it by
    * prices. `besu-eth/besu` @ `c2addd9424` is the closest in shape:
    * `MainnetEVMs.frontierOperations(GasCalculator, EvmConfiguration)` sits in
    * its `evm` module, whose `build.gradle` names `:ethereum:core` zero times,
    * while `MainnetProtocolSpecs` sits in the layer above.
    * `ethereumclassic/core-geth` @ `4185df450` -- the multi-network one --
    * calls it `newBaseInstructionSet` at `core/vm/jump_table.go:245` and leaves
    * the fork's name in the comment above it, because a root shared by every
    * network it serves cannot carry one network's name for it.
    * `ethereum/go-ethereum-pow` @ `v1.10.26` and `ethereum/go-ethereum` @
    * `6bb0588ad` both hold it as `newFrontierInstructionSet` in the same
    * package.
    *
    * **The asymmetry with [[PrecompileSet]] is the field's rather than an
    * inconsistency here.** In `ethereum-optimism/op-geth` @ `86be6726f` and
    * `ronin/ronin` @ `84f1c2260`, every chain-specific token under `core/vm`
    * is in the precompile path -- the registry and the selection beside it --
    * and `jump_table.go` and `instructions.go` carry none in either. Membership
    * is where networks diverge; the instruction set's shape is the machine's.
    */
  def original(schedule: GasSchedule): OpcodeTable =
    val fixed: Map[Opcode, BigInt] =
      Map(
        Opcode.Stop -> schedule.zero,
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

    val computed: Set[Opcode] = Opcode.values.toSet -- fixed.keySet -- laterThanOriginal
    val all =
      fixed.map((opcode, gas) => Operation(opcode, Cost.Fixed(gas))) ++
        computed.map(opcode => Operation(opcode, Cost.Computed))
    new OpcodeTable(all.map(operation => operation.opcode.code -> operation).toMap)

  /** Operations [[Opcode]] names that the EVM was not originally specified
    * with.
    *
    * The enum spans every fork this build knows, because a byte's meaning does
    * not change once it has one -- so [[original]] SELECTS from it rather than
    * being it. Without this the first operation a proposal adds would already be
    * in the table it was meant to add it to, and its delta would be
    * unobservable: the fork would be correct and the seam would have proved
    * nothing.
    *
    * **This is EVM history and not a network's configuration**, which is why it
    * belongs in the machine: `DELEGATECALL` entered through EIP-7, and a
    * network that runs it runs it by adopting EIP-7. No network disagrees about
    * that, so nothing here is one network's word against another's.
    *
    * **A spec pins [[original]]'s size as a counted number**, so an operation
    * added to the enum and forgotten here fails loudly rather than joining the
    * root in silence. That direction is the one worth failing.
    */
  private def laterThanOriginal: Set[Opcode] = Set(Opcode.DelegateCall)

  /** The three families whose members differ only in a count, and which are
    * priced alike across all of them.
    */
  private def stackFamilies: Set[Opcode] =
    Opcode.values.toSet.filter { opcode =>
      Opcode.isPush(opcode) ||
      (opcode.code >= Opcode.Dup1.code && opcode.code <= Opcode.Swap16.code)
    }
