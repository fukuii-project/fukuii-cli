package org.fukuii.evm

/** The ways execution ends other than by running off the end or stopping.
  *
  * ==There is no cheap failure here, and the absence is load-bearing==
  *
  * `REVERT` is EIP-140 and this build predates it. Every member below is an *exceptional*
  * halt, and the executable specification says what that costs:
  * "the EVM has experienced an exceptional halt. This causes execution to
  * immediately end with all gas being consumed." So there is no member for a
  * cheap failure, because at this fork there is no such thing — a caller
  * distinguishes success from failure and never distinguishes two failures by
  * how much gas came back.
  *
  * The set is exactly the seven `ExceptionalHalt` subclasses of
  * `forks/frontier/vm/exceptions.py` at `ccaaaba58`, counted there rather than
  * recalled. One of them is unreachable from any operation, and is declared
  * because the set is the specification's rather than this machine's.
  */
enum Halt:

  /** A pop against an empty stack. */
  case StackUnderflow

  /** A push against a stack already at [[Stack.Limit]]. */
  case StackOverflow

  /** An operation costing more than the frame has left. */
  case OutOfGas

  /** A byte that names no operation at this fork. Carries the byte, because a
    * caller diagnosing a chain split needs to know which one.
    */
  case InvalidOpcode(code: Int)

  /** A jump to a position that is not a `JUMPDEST`. */
  case InvalidJumpDestination

  /** An invocation nested deeper than the machine permits.
    *
    * The operations that nest refuse rather than exceed the limit, so this is
    * the guard behind them rather than the one they use, and a caller reaching
    * it has built a frame no operation would have.
    */
  case StackDepthLimit

  /** A create whose destination address is already in use.
    *
    * Not reachable from `CREATE`, which answers zero rather than halting. The
    * specification raises this for a creating TRANSACTION instead, which is a
    * layer above this one.
    */
  case AddressCollision
