package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.types.Log

/** One invocation's working state: where it is, what it holds, and what it has
  * left to spend.
  *
  * ==What the invocation IS lives on [[message]]; what it is DOING lives here==
  *
  * The caller, the account it runs as, the value sent and the input given do
  * not change while it runs, so they are a value it carries rather than fields
  * that could drift. Everything below is the part that moves.
  *
  * ==Mutable, following the specification and what sits below it==
  *
  * The executable specification's frame is a mutable record and every operation
  * updates it in place; [[Stack]] and [[Memory]] here are already mutable for
  * the same reason. A frame rebuilt per instruction would allocate once per
  * operation to express a machine that is definitionally a state that changes.
  *
  * ==Gas leaves only through charge==
  *
  * [[gasLeft]] is readable but is reduced only by [[charge]], which refuses
  * rather than going negative. An operation that spends by assignment can
  * overspend, and an overspent frame is indistinguishable afterwards from one
  * that was affordable.
  */
final class Frame(
    val message: Message,
    val code: Code,
    initialGas: BigInt,
    val registeredByAncestors: Set[Address] = Set.empty,
    reachedBeforeEntry: Set[Address] = Set.empty,
    slotsReachedBeforeEntry: Set[(Address, Word)] = Set.empty
):

  /** The position of the instruction about to run. */
  var pc: Int = 0

  var gasLeft: BigInt = initialGas

  /** Gas this invocation has earned back, which is not gas it may spend.
    *
    * A refund is settled against the transaction after execution ends rather
    * than being returned to the frame, so it is counted here and never added to
    * [[gasLeft]]. Only one operation at this fork earns any: clearing a storage
    * slot that held a value.
    *
    * It sits on the frame because the specification and besu both put it there,
    * and because that is what discards it correctly -- an invocation that ends
    * exceptionally has its frame dropped, and a refund it had earned goes with
    * it. go-ethereum reaches the same outcome by keeping the counter on state
    * and rewinding it, which is the same rule expressed against a different
    * home.
    */
  var refundCounter: BigInt = BigInt(0)

  /** False once an operation has ended execution deliberately. */
  var running: Boolean = true

  /** What the invocation hands back. Empty until an operation sets it, which
    * `RETURN` and `REVERT` do.
    */
  var output: Bytes = Bytes.Empty

  /** What the invocation this one most recently started handed back.
    *
    * ==Not [[output]], and confusing the two is a state root apart==
    *
    * [[output]] is what THIS invocation gives its caller; this is what its LAST
    * CHILD gave it. The proposal draws the same line, placing the buffer "of
    * the caller" and populating it after a call-like operation rather than
    * during one.
    *
    * ==Whoever starts an invocation sets this, at every exit that operation
    * has==
    *
    * Nothing propagates it: a child's own buffer dies with the child's frame,
    * and an operation that refuses before a frame exists leaves an empty
    * buffer rather than the one its caller was holding. So the rule is a
    * property of assigning at every exit, which is why the operations that
    * nest write it on the paths that run nothing as well as on the paths that
    * do.
    *
    * ==Per frame rather than shared, which is a choice the proposal permits
    * either way==
    *
    * It sanctions sharing one buffer across frames, "because at most one will
    * be non-empty at any time", and `ethereum/go-ethereum-pow` @ `v1.10.26`
    * takes that route with a field on its interpreter cleared on entry to
    * every invocation (`core/vm/interpreter.go:64,130`). Sharing it here would
    * mean adding that clearing step for no reason but to emulate a per-frame
    * field, so the field is per frame -- which is where the executable
    * specification puts it too, beside its own output
    * (`ethereum/execution-specs` @ `20f7f6271a`,
    * `src/ethereum/forks/byzantium/vm/__init__.py:143`).
    */
  var returnData: Bytes = Bytes.Empty

  /** What this invocation has emitted, oldest first.
    *
    * Order is part of the record rather than an artifact: a receipt lists logs
    * in the order they were emitted, and a nested invocation's are spliced in
    * where its call sits among its caller's.
    */
  var logs: Vector[Log] = Vector.empty

  /** The accounts this invocation has registered for destruction.
    *
    * Registration is all that happens here. The removal itself belongs to
    * whatever ends the transaction, because a registered account goes on
    * answering reads -- and goes on being callable -- until then, and because a
    * registration inside an invocation that later fails is discarded with the
    * rest of its writes.
    */
  var accountsToDelete: Set[Address] = Set.empty

  /** The accounts this invocation has reached in a way that could have changed
    * them, whether or not it did.
    *
    * ==Candidates, never a conclusion==
    *
    * Recorded unfiltered: whether a member is left holding nothing is asked
    * once, by whatever ends the transaction, rather than at each site that
    * records one. The specification asks at both ends and reaches the same set,
    * because its end-of-transaction check re-asks -- an account that stops
    * holding nothing in between is dropped by both. The reverse needs a balance
    * to fall to nothing, which takes either code or a transaction count, and
    * either of those makes the account non-empty by a different term.
    *
    * ==Recording is not the state-side act, and only one of the two is undone
    * here==
    *
    * [[WorldState.touch]] brings an account into being and is reversed by a
    * snapshot; this records that the account was reached and is not. What
    * discards it is the absence of an act -- an invocation that ends
    * exceptionally is never taken up, so what it recorded goes with its logs and
    * its registrations. [[EvmRules.touchSurvivesFailure]] is the one exception,
    * and it is read where the taking-up would have happened rather than here.
    */
  var touchedAccounts: Set[Address] = Set.empty

  /** The accounts this invocation, or one it is nested inside, has already
    * reached in a way a warm-and-cold scheme charges the reduced price for.
    *
    * ==Seeded with a COPY of what the caller held, which is what makes a revert
    * fall out==
    *
    * A caller is suspended while its callee runs, so the set handed down cannot
    * change underneath; the callee accumulates into its own copy, and
    * [[Interpreter]] takes that copy up only where the callee stopped normally.
    * An invocation that failed simply is not taken up, so what it reached is
    * discarded with its logs and its registrations -- *"if a scope reverts, the
    * access lists should be in the state they were in before that scope was
    * entered"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2929.md`, Final).
    *
    * **That is why this is ONE accumulator and not the pair
    * [[registeredByAncestors]] splits into.** A registration spans the chain of
    * callers for a question about payment while only this frame's own set is
    * acted on, so the two have to be told apart there. Here the question and the
    * accumulation are the same set, and merging is the whole of what an ancestor
    * contributes.
    *
    * ==The outermost invocation is seeded by whatever settles the transaction==
    *
    * Not by anything here. What belongs in that seed is the sender, the
    * recipient or the address being created, every precompile, and whatever the
    * transaction declared ahead of running -- and NOT the block's beneficiary,
    * which a later proposal adds.
    */
  var accessedAddresses: Set[Address] = reachedBeforeEntry

  /** The storage slots reached the same way, keyed by the account they belong
    * to as well as by the slot.
    *
    * The account is part of the key rather than implied by the frame: the
    * specification keys the same set on a pair, and an invocation can be
    * re-entered under a different account with the same slot numbers.
    */
  var accessedStorageKeys: Set[(Address, Word)] = slotsReachedBeforeEntry

  val stack: Stack = new Stack

  val memory: Memory = new Memory

  /** Spends `amount`, or reports that it could not be spent.
    *
    * The check is before the subtraction rather than after it, because at this
    * fork a frame that cannot pay keeps nothing: the caller consumes the
    * remainder, so a negative balance would be arithmetic nobody ever reads.
    */
  def charge(amount: BigInt): Either[Halt, Unit] =
    if gasLeft < amount then Left(Halt.OutOfGas)
    else
      gasLeft -= amount
      Right(())

  /** Whether `address` is already registered for destruction by this invocation
    * or by one it is nested inside.
    *
    * The registration a destruction earns is paid once per account per
    * transaction, so the question spans the whole chain of callers rather than
    * this frame. Ancestors' registrations arrive as a value at construction
    * because a caller is suspended while its callee runs, so the set it would
    * be asked for cannot change in the meantime.
    */
  def alreadyRegistered(address: Address): Boolean =
    accountsToDelete.contains(address) || registeredByAncestors.contains(address)

  /** Everything registered for destruction that a nested invocation must treat
    * as already paid for.
    */
  def registeredSoFar: Set[Address] = accountsToDelete | registeredByAncestors
