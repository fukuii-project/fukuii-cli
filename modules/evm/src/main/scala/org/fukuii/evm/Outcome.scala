package org.fukuii.evm

import org.fukuii.bytes.Bytes

/** How an invocation ended.
  *
  * ==Two ends, not five==
  *
  * At this fork there is no cheap failure. An operation that cannot proceed
  * raises an exceptional halt, and the specification says what that costs:
  * execution ends immediately with all remaining gas consumed. So a caller
  * distinguishes success from failure and never distinguishes two failures by
  * what came back, which is why the reasons in [[Halt]] are diagnostic rather
  * than semantic. `REVERT`, which is the first cheap failure, arrives at a
  * later fork and will be a third member here rather than a flag on the second.
  */
enum Outcome:

  /** Execution ended normally, either by an operation stopping it or by the
    * program counter running off the end of the code.
    */
  case Stopped(gasLeft: BigInt, output: Bytes)

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
