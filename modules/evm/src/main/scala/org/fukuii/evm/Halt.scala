package org.fukuii.evm

/** Why execution ended exceptionally.
  *
  * ==Every member here consumes everything, and the cheap failure is not one
  * of them==
  *
  * The specification states what an exceptional halt costs: "the EVM has
  * experienced an exceptional halt. This causes execution to immediately end
  * with all gas being consumed." A failure that hands the remainder back is a
  * different result and is [[Outcome.Reverted]], which carries no member of
  * this type at all -- the specification separates them the same way, deriving
  * its `Revert` from the common base rather than from `ExceptionalHalt`.
  *
  * ==The set is the specification's, and membership is not a claim about this
  * build==
  *
  * Exactly the ten `ExceptionalHalt` subclasses of
  * `forks/byzantium/vm/exceptions.py` at `ethereum/execution-specs` @
  * `20f7f6271a`, counted in the file rather than recalled: the same sweep
  * returns seven for `forks/frontier` and for `forks/spurious_dragon`, so it
  * discriminates, and zero for a class name no fork declares.
  *
  * Being a member says the specification declares the reason, never that any
  * operation this build runs raises it. [[AddressCollision]] is the standing
  * case and is unreachable from any operation at any fork.
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

  /** A write attempted by an invocation that was asked not to write. */
  case WriteInStaticContext

  /** A read reaching past the end of the buffer it reads from.
    *
    * Only one buffer refuses such a read. The three copying operations whose
    * source is the call data or an account's code pad with zeros instead, so a
    * read wholly past the end of those is a run of zeros and not a fault.
    */
  case OutOfBoundsRead

  /** Input a natively-answered address could not make sense of.
    *
    * Distinct from running out of gas, which is what a caller sees where a
    * precompile converts this before returning.
    */
  case InvalidParameter

  /** A create whose destination address is already in use.
    *
    * Not reachable from `CREATE`, which answers zero rather than halting. The
    * specification raises this for a creating TRANSACTION instead, which is a
    * layer above this one.
    */
  case AddressCollision
