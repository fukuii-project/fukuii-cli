package org.fukuii.evm

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Every operation the baseline table holds, and what it charges.
  *
  * ==Where the expected values come from==
  *
  * `forks/frontier/vm/` at `ccaaaba58`. The numbers are derived rather than
  * transcribed: each operation's implementation was located through
  * `instructions/__init__.py`'s `op_implementation` map, its `charge_gas` call
  * read, and the argument resolved against `gas.py`'s constants. An argument
  * that is one constant is a settled price; anything else is a price the
  * operation works out, and is recorded as such rather than as a number nobody
  * could check.
  *
  * `SELFDESTRUCT` is the one row that derivation cannot reach on its own:
  * `instructions/system.py` binds `gas_cost = GasCosts.ZERO` before charging it,
  * so the charge names a local rather than a constant. It is read from that
  * assignment instead.
  */
class OpcodeTableSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val table = OpcodeTable.baseline(GasSchedule.Baseline)

  private val expected = Table(
    ("opcode", "cost"),
    (Opcode.Stop, Cost.Fixed(BigInt(0))),
    (Opcode.Add, Cost.Fixed(BigInt(3))),
    (Opcode.Mul, Cost.Fixed(BigInt(5))),
    (Opcode.Sub, Cost.Fixed(BigInt(3))),
    (Opcode.Div, Cost.Fixed(BigInt(5))),
    (Opcode.SDiv, Cost.Fixed(BigInt(5))),
    (Opcode.Mod, Cost.Fixed(BigInt(5))),
    (Opcode.SMod, Cost.Fixed(BigInt(5))),
    (Opcode.AddMod, Cost.Fixed(BigInt(8))),
    (Opcode.MulMod, Cost.Fixed(BigInt(8))),
    (Opcode.Exp, Cost.Computed),
    (Opcode.SignExtend, Cost.Fixed(BigInt(5))),
    (Opcode.Lt, Cost.Fixed(BigInt(3))),
    (Opcode.Gt, Cost.Fixed(BigInt(3))),
    (Opcode.SLt, Cost.Fixed(BigInt(3))),
    (Opcode.SGt, Cost.Fixed(BigInt(3))),
    (Opcode.Eq, Cost.Fixed(BigInt(3))),
    (Opcode.IsZero, Cost.Fixed(BigInt(3))),
    (Opcode.And, Cost.Fixed(BigInt(3))),
    (Opcode.Or, Cost.Fixed(BigInt(3))),
    (Opcode.Xor, Cost.Fixed(BigInt(3))),
    (Opcode.Not, Cost.Fixed(BigInt(3))),
    (Opcode.Byte, Cost.Fixed(BigInt(3))),
    (Opcode.Keccak256, Cost.Computed),
    (Opcode.Address, Cost.Fixed(BigInt(2))),
    (Opcode.Balance, Cost.Fixed(BigInt(20))),
    (Opcode.Origin, Cost.Fixed(BigInt(2))),
    (Opcode.Caller, Cost.Fixed(BigInt(2))),
    (Opcode.CallValue, Cost.Fixed(BigInt(2))),
    (Opcode.CallDataLoad, Cost.Fixed(BigInt(3))),
    (Opcode.CallDataSize, Cost.Fixed(BigInt(2))),
    (Opcode.CallDataCopy, Cost.Computed),
    (Opcode.CodeSize, Cost.Fixed(BigInt(2))),
    (Opcode.CodeCopy, Cost.Computed),
    (Opcode.GasPrice, Cost.Fixed(BigInt(2))),
    (Opcode.ExtCodeSize, Cost.Fixed(BigInt(20))),
    (Opcode.ExtCodeCopy, Cost.Computed),
    (Opcode.BlockHash, Cost.Fixed(BigInt(20))),
    (Opcode.Coinbase, Cost.Fixed(BigInt(2))),
    (Opcode.Timestamp, Cost.Fixed(BigInt(2))),
    (Opcode.Number, Cost.Fixed(BigInt(2))),
    (Opcode.Difficulty, Cost.Fixed(BigInt(2))),
    (Opcode.GasLimit, Cost.Fixed(BigInt(2))),
    (Opcode.Pop, Cost.Fixed(BigInt(2))),
    (Opcode.MLoad, Cost.Computed),
    (Opcode.MStore, Cost.Computed),
    (Opcode.MStore8, Cost.Computed),
    (Opcode.SLoad, Cost.Fixed(BigInt(50))),
    (Opcode.SStore, Cost.Computed),
    (Opcode.Jump, Cost.Fixed(BigInt(8))),
    (Opcode.JumpI, Cost.Fixed(BigInt(10))),
    (Opcode.Pc, Cost.Fixed(BigInt(2))),
    (Opcode.MSize, Cost.Fixed(BigInt(2))),
    (Opcode.Gas, Cost.Fixed(BigInt(2))),
    (Opcode.JumpDest, Cost.Fixed(BigInt(1))),
    (Opcode.Push1, Cost.Fixed(BigInt(3))),
    (Opcode.Push2, Cost.Fixed(BigInt(3))),
    (Opcode.Push3, Cost.Fixed(BigInt(3))),
    (Opcode.Push4, Cost.Fixed(BigInt(3))),
    (Opcode.Push5, Cost.Fixed(BigInt(3))),
    (Opcode.Push6, Cost.Fixed(BigInt(3))),
    (Opcode.Push7, Cost.Fixed(BigInt(3))),
    (Opcode.Push8, Cost.Fixed(BigInt(3))),
    (Opcode.Push9, Cost.Fixed(BigInt(3))),
    (Opcode.Push10, Cost.Fixed(BigInt(3))),
    (Opcode.Push11, Cost.Fixed(BigInt(3))),
    (Opcode.Push12, Cost.Fixed(BigInt(3))),
    (Opcode.Push13, Cost.Fixed(BigInt(3))),
    (Opcode.Push14, Cost.Fixed(BigInt(3))),
    (Opcode.Push15, Cost.Fixed(BigInt(3))),
    (Opcode.Push16, Cost.Fixed(BigInt(3))),
    (Opcode.Push17, Cost.Fixed(BigInt(3))),
    (Opcode.Push18, Cost.Fixed(BigInt(3))),
    (Opcode.Push19, Cost.Fixed(BigInt(3))),
    (Opcode.Push20, Cost.Fixed(BigInt(3))),
    (Opcode.Push21, Cost.Fixed(BigInt(3))),
    (Opcode.Push22, Cost.Fixed(BigInt(3))),
    (Opcode.Push23, Cost.Fixed(BigInt(3))),
    (Opcode.Push24, Cost.Fixed(BigInt(3))),
    (Opcode.Push25, Cost.Fixed(BigInt(3))),
    (Opcode.Push26, Cost.Fixed(BigInt(3))),
    (Opcode.Push27, Cost.Fixed(BigInt(3))),
    (Opcode.Push28, Cost.Fixed(BigInt(3))),
    (Opcode.Push29, Cost.Fixed(BigInt(3))),
    (Opcode.Push30, Cost.Fixed(BigInt(3))),
    (Opcode.Push31, Cost.Fixed(BigInt(3))),
    (Opcode.Push32, Cost.Fixed(BigInt(3))),
    (Opcode.Dup1, Cost.Fixed(BigInt(3))),
    (Opcode.Dup2, Cost.Fixed(BigInt(3))),
    (Opcode.Dup3, Cost.Fixed(BigInt(3))),
    (Opcode.Dup4, Cost.Fixed(BigInt(3))),
    (Opcode.Dup5, Cost.Fixed(BigInt(3))),
    (Opcode.Dup6, Cost.Fixed(BigInt(3))),
    (Opcode.Dup7, Cost.Fixed(BigInt(3))),
    (Opcode.Dup8, Cost.Fixed(BigInt(3))),
    (Opcode.Dup9, Cost.Fixed(BigInt(3))),
    (Opcode.Dup10, Cost.Fixed(BigInt(3))),
    (Opcode.Dup11, Cost.Fixed(BigInt(3))),
    (Opcode.Dup12, Cost.Fixed(BigInt(3))),
    (Opcode.Dup13, Cost.Fixed(BigInt(3))),
    (Opcode.Dup14, Cost.Fixed(BigInt(3))),
    (Opcode.Dup15, Cost.Fixed(BigInt(3))),
    (Opcode.Dup16, Cost.Fixed(BigInt(3))),
    (Opcode.Swap1, Cost.Fixed(BigInt(3))),
    (Opcode.Swap2, Cost.Fixed(BigInt(3))),
    (Opcode.Swap3, Cost.Fixed(BigInt(3))),
    (Opcode.Swap4, Cost.Fixed(BigInt(3))),
    (Opcode.Swap5, Cost.Fixed(BigInt(3))),
    (Opcode.Swap6, Cost.Fixed(BigInt(3))),
    (Opcode.Swap7, Cost.Fixed(BigInt(3))),
    (Opcode.Swap8, Cost.Fixed(BigInt(3))),
    (Opcode.Swap9, Cost.Fixed(BigInt(3))),
    (Opcode.Swap10, Cost.Fixed(BigInt(3))),
    (Opcode.Swap11, Cost.Fixed(BigInt(3))),
    (Opcode.Swap12, Cost.Fixed(BigInt(3))),
    (Opcode.Swap13, Cost.Fixed(BigInt(3))),
    (Opcode.Swap14, Cost.Fixed(BigInt(3))),
    (Opcode.Swap15, Cost.Fixed(BigInt(3))),
    (Opcode.Swap16, Cost.Fixed(BigInt(3))),
    (Opcode.Log0, Cost.Computed),
    (Opcode.Log1, Cost.Computed),
    (Opcode.Log2, Cost.Computed),
    (Opcode.Log3, Cost.Computed),
    (Opcode.Log4, Cost.Computed),
    (Opcode.Create, Cost.Computed),
    (Opcode.Call, Cost.Computed),
    (Opcode.CallCode, Cost.Computed),
    (Opcode.Return, Cost.Computed),
    (Opcode.SelfDestruct, Cost.Fixed(BigInt(0)))
  )

  property("the baseline table prices every operation as the specification does") {
    forAll(expected) { (opcode: Opcode, cost: Cost) =>
      assert(
        table.operationAt(opcode.code) == Some(Operation(opcode, cost)),
        s"the byte 0x${opcode.code.toHexString} must run ${opcode.toString} at ${cost.toString}"
      )
    }
  }

  property("the baseline table holds the operations the machine started with and no other byte") {
    // 129, counted, against a vocabulary of 130. **The gap is the point**: the
    // enum spans forks and the baseline selects from it, so an operation a later
    // proposal adds must be absent here or its delta would be unobservable --
    // the fork correct, the seam having proved nothing.
    //
    // Pinned as a NUMBER rather than against the enum, deliberately. Comparing
    // the two would make this assertion restate the expression it is checking,
    // and an operation added to the enum and forgotten in the baseline's own
    // exclusion would then join the baseline in silence. A counted figure fails.
    assert(
      table.size == 129 && !table.contains(Opcode.DelegateCall),
      "a byte outside this fork's set must run nothing, and every operation in it must run"
    )
  }

  property("removing an operation leaves its byte running nothing") {
    val reduced = table.removing(Opcode.SelfDestruct)
    assert(
      reduced.operationAt(Opcode.SelfDestruct.code).isEmpty && reduced.size == table.size - 1,
      "a network that takes an operation out is expressing exactly what scroll-tech/go-ethereum expresses by nulling its table slot"
    )
  }

  property("removing an operation leaves every other one alone") {
    val reduced = table.removing(Opcode.SelfDestruct)
    assert(
      reduced.opcodes == table.opcodes - Opcode.SelfDestruct,
      "subtraction is one entry, not a rebuild"
    )
  }

  property("adding an operation for a byte already held replaces it") {
    val repriced = table.adding(Operation(Opcode.Balance, Cost.Fixed(BigInt(700))))
    assert(
      repriced.operationAt(Opcode.Balance.code) == Some(Operation(Opcode.Balance, Cost.Fixed(BigInt(700)))) &&
        repriced.size == table.size,
      "a repricing is an entry replacing an entry, which is how core-geth applies one"
    )
  }

  property("a schedule change moves every operation priced from what changed") {
    val dearer = OpcodeTable.baseline(GasSchedule.Baseline.copy(veryLow = BigInt(30)))
    assert(
      dearer.operationAt(Opcode.Add.code) == Some(Operation(Opcode.Add, Cost.Fixed(BigInt(30)))) &&
        dearer.operationAt(Opcode.Mul.code) == table.operationAt(Opcode.Mul.code),
      "the tier is what most operations name, so moving it moves them together and leaves the rest"
    )
  }
