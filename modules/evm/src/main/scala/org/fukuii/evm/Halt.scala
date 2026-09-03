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
  * Exactly the eleven `ExceptionalHalt` subclasses of
  * `forks/london/vm/exceptions.py` at `ethereum/execution-specs` @
  * `20f7f6271a`, counted in the file rather than recalled: the same sweep
  * returns ten for `forks/byzantium` and for `forks/berlin`, seven for
  * `forks/frontier` and for `forks/spurious_dragon`, so it discriminates, and
  * zero for a class name no fork declares.
  *
  * **The set grows with a fork and the count is a reading, not a constant.**
  * It stood at ten while `forks/berlin` was the newest fork this build modeled;
  * [[InvalidContractPrefix]] is the eleventh and arrives with London. Re-run the
  * sweep rather than trusting this sentence.
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
    * ==Reached by this build, and indistinguishable from [[OutOfGas]] to a
    * caller==
    *
    * Both are exceptional halts, so an invocation reaching either fails, keeps
    * nothing and hands back nothing. What the two separate is which fact a node
    * can report about a divergence, never a chain outcome -- and the
    * specification underlines that by raising this reason where a curve rejects
    * a point and then converting it to running out of gas before returning.
    */
  case InvalidParameter

  /** A create whose destination address is already in use.
    *
    * Not reachable from `CREATE`, which answers zero rather than halting. The
    * specification raises this for a creating TRANSACTION instead, which is a
    * layer above this one.
    */
  case AddressCollision

  /** A deployment whose code begins with the byte a proposal reserved.
    *
    * ==Indistinguishable from [[OutOfGas]] to a caller, and a member anyway==
    *
    * The specification raises this and its over-long-code refusal into one
    * handler, which restores the state, zeroes the gas and empties the output
    * for both -- so no state root, receipt or gas figure separates them, and no
    * published fixture format records an exceptional halt's identity at all.
    *
    * **That is not the membership test.** This type's set is the
    * specification's, and [[AddressCollision]] is already here while being
    * unreachable from every operation at every fork. What earns a member is the
    * specification declaring the reason.
    *
    * It is worth saying why that reads as a reversal and is not one. The
    * over-long refusal earns no member of this type, on the ground that the
    * forks either side of the proposal introducing it declare the same
    * exceptions; the sweep those two counts come from returns eleven here
    * against ten for the fork below, the difference being this reason alone.
    * One rule, two inputs, two answers.
    */
  case InvalidContractPrefix
