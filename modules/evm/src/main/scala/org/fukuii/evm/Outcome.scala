package org.fukuii.evm

import org.fukuii.bytes.Bytes

/** How an invocation ended.
  *
  * ==Three ends, and the middle one keeps what the last one takes==
  *
  * A failure that consumes everything and a failure that hands the remainder
  * back are different results, and a caller has to tell them apart: the second
  * returns gas to the frame that started it and carries a payload the first
  * cannot have. The two differ in exactly those two fields and in nothing else
  * -- the state an invocation wrote is discarded on both, and both report
  * failure to whatever settles the transaction.
  *
  * The specification writes the difference as two exception handlers over one
  * body: the exceptional one sets `evm.gas_left = Uint(0)` and `evm.output =
  * b""` before recording the error, and the cheap one records the error alone
  * (`ethereum/execution-specs` @ `20f7f6271a`,
  * `src/ethereum/forks/byzantium/vm/interpreter.py:269-276`). The rollback is
  * outside both, on the error rather than on its kind
  * (`:278-279`).
  *
  * ==The cheap failure carries no reason, and that is the proposal's answer
  * rather than an omission here==
  *
  * [[Halted]] names why it halted because a caller diagnosing a divergence
  * needs it. [[Reverted]] has nothing to name: the proposal calls its payload
  * an *"error message"* and says its content *"is not defined by this EIP"*
  * (`ethereum/EIPs` @ `9e393a79`, `EIPS/eip-140.md`, Final). The specification
  * agrees structurally -- its `Revert` is a sibling of `ExceptionalHalt` under
  * a common base rather than a member of it
  * (`src/ethereum/forks/byzantium/vm/exceptions.py`), so there is no reason
  * class for it to carry.
  */
enum Outcome:

  /** Execution ended normally, either by an operation stopping it or by the
    * program counter running off the end of the code.
    */
  case Stopped(gasLeft: BigInt, output: Bytes)

  /** Execution was abandoned deliberately. What the invocation wrote is
    * discarded, what it had not spent is handed back, and the bytes it named
    * reach its caller.
    */
  case Reverted(gasLeft: BigInt, output: Bytes)

  /** Execution ended exceptionally. No gas remains, which is why none is
    * reported.
    */
  case Halted(halt: Halt)

/** An operation the table admits and this build cannot run.
  *
  * ==This is not an outcome, and the type keeps it from becoming one==
  *
  * Every member of [[Outcome]] is a result a chain can legitimately reach, and
  * an exceptional halt is among them: at this fork a transaction that halts is
  * a valid transaction that consumed its gas. An operation that has not been
  * built yet is a different thing entirely, and a caller that mapped it onto a
  * halt would be recording an unbuilt operation as a consensus result. So it is
  * returned as a separate type rather than as another member, which makes that
  * confusion a type error instead of a judgment call.
  *
  * go-ethereum forbids the same condition earlier, by refusing to construct a
  * table whose entries have no implementation. That is the stronger form and it
  * is available only to a machine that is complete; this one is complete for the
  * operations it defines, and the condition that outlives that is narrower:
  * every operation runs, and a table can still ask for one to be priced in a
  * way this build does not price it. A chain configuration produces the table,
  * so that is a reachable disagreement between the table and the machine rather
  * than a gap waiting to be filled.
  *
  * Reversing trigger: the table and the machine can no longer disagree about
  * how an operation is priced -- at which point nothing can construct this and
  * it is deleted along with the result type that carries it.
  */
final case class Unsupported(opcode: Opcode)
