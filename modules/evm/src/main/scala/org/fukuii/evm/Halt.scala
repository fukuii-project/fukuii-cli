package org.fukuii.evm

/** The ways execution ends other than by running off the end or stopping.
  *
  * ==Frontier has no REVERT, and the absence is load-bearing==
  *
  * `REVERT` arrives at Byzantium. Here every member below is an *exceptional*
  * halt, and the executable specification says what that costs:
  * "the EVM has experienced an exceptional halt. This causes execution to
  * immediately end with all gas being consumed." So there is no member for a
  * cheap failure, because at this fork there is no such thing — a caller
  * distinguishes success from failure and never distinguishes two failures by
  * how much gas came back.
  *
  * The set is exactly the seven `ExceptionalHalt` subclasses of
  * `forks/frontier/vm/exceptions.py` at `ccaaaba58`, counted there rather than
  * recalled. Two of them cannot arise until a later phase builds the operation
  * that raises them, and they are declared now because the set is the
  * specification's rather than this phase's.
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

  /** A call or create nested deeper than the machine permits. */
  case StackDepthLimit

  /** A create whose destination address is already in use. */
  case AddressCollision
