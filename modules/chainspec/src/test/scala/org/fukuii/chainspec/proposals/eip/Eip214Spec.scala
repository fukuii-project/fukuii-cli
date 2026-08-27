package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.EvmFixtures.MapWorldState
import org.fukuii.evm.{Code, Cost, Environment, EvmFixtures, Frame, Halt, Interpreter, Opcode, Operation, Outcome, Word}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-214 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip214.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The byte and the pricing shape below are read from the proposal's own text.
  * What the operation DOES -- the value it hands its callee, the storage it runs
  * under, and the nine operations that then refuse -- is the machine's, and
  * `org.fukuii.evm.InterpreterSpec` and `org.fukuii.evm.InvocationSpec` certify
  * it there.
  */
class Eip214Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip214.component)

  /** An account this spec's programs call into. */
  private val named: Address = EvmFixtures.address(0x33)

  /** The account [[EvmFixtures.message]] runs as, which is the one the caller's
    * own trailing store lands under.
    */
  private val runsAs: Address = EvmFixtures.address(0x22)

  private def push1(value: Int): Seq[Int] = Seq(0x60, value & 0xff)

  private def push2(value: Int): Seq[Int] = Seq(0x61, (value >> 8) & 0xff, value & 0xff)

  private def push20(address: Address): Seq[Int] =
    0x73 +: (0 until Address.Width).map(index => address.toBytes(index) & 0xff)

  private def codeOf(program: Seq[Int]): Bytes = Bytes.fromArray(program.map(_.toByte).toArray)

  /** Writes one to the caller's own slot two.
    *
    * Run after the call, this is what separates "the callee's store was
    * refused" from "the program never reached the call at all" -- a byte that
    * runs nothing halts the caller, and a halted caller's writes are rolled back
    * along with the callee's.
    */
  private val recordingThatItFinished: Seq[Int] = push1(0x01) ++ push1(0x02) ++ Seq(0x55, 0x00)

  /** Six operands and the operation the document introduces. */
  private val asking: Seq[Int] =
    push1(0) ++ push1(0) ++ push1(0) ++ push1(0) ++ push20(named) ++ push2(40000) ++
      (0xfa +: recordingThatItFinished)

  /** The same call made ordinarily, which needs a seventh operand -- the value
    * this form does not take.
    */
  private val askingOrdinarily: Seq[Int] =
    push1(0) ++ push1(0) ++ push1(0) ++ push1(0) ++ push1(0) ++ push20(named) ++ push2(40000) ++
      (0xf1 +: recordingThatItFinished)

  /** Writes 42 to slot one and stops. */
  private val storing: Seq[Int] = push1(0x2a) ++ push1(0x01) ++ Seq(0x55, 0x00)

  /** Runs `program` under `rules`, over a world holding [[storing]] at
    * [[named]].
    *
    * Answers with the ENVIRONMENT rather than the world under it, because the
    * environment's own world is the journal the invocation writes through and
    * nothing here commits it.
    */
  private def runUnder(rules: org.fukuii.evm.EvmRules, program: Seq[Int]): (Frame, Environment) =
    val holder = new MapWorldState
    holder.codes(named) = codeOf(storing)
    val environment: Environment = EvmFixtures.environmentUnder(rules, holder)
    val frame = new Frame(EvmFixtures.message(transfersValue = true), Code(codeOf(program)), BigInt(1000000))
    val _ = Interpreter.run(frame, environment)
    (frame, environment)

  private def run(program: Seq[Int]): (Frame, Environment) = runUnder(adopted.evm, program)

  "adopting EIP-214" should "put an operation at 0xfa" in
    // "Opcode: 0xfa", ethereum/EIPs @ 9e393a79, EIPS/eip-214.md, Final.
    assert(
      adopted.evm.table.operationAt(0xfa).contains(Operation(Opcode.StaticCall, Cost.Computed)),
      "0xfa runs something other than the operation this document introduces"
    )

  it should "have run nothing at that byte before it was adopted" in
    // The control. Without it the case above passes against a table that already
    // carried the entry, and an absent entry is what every height before this
    // document runs.
    assert(
      base.evm.table.operationAt(0xfa).isEmpty,
      "the preceding table already ran an operation at this byte"
    )

  it should "price it from its operands rather than from a figure" in
    // The document names no gas figure at all, and what this operation costs
    // turns on the memory it reaches and the gas it forwards. A fixed entry
    // would be a price standing where a computation belongs.
    assert(
      adopted.evm.table.operationAt(0xfa).map(_.cost).contains(Cost.Computed),
      "an operation whose price is settled from its operands was given a figure"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.table.size == base.evm.table.size + 1,
      "adopting a document that introduces one operation moved the table by some other amount"
    )

  it should "settle that entry and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member reached
    // by accident fails as loudly as the named one failing to move.
    assert(
      adopted.evm == base.evm.copy(table = base.evm.table.adding(Operation(Opcode.StaticCall, Cost.Computed))),
      "the adopting rules differ from the earlier ones by something other than the entry"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // The document names no price, so it reaches the schedule nowhere. A delta
    // that rebuilt it would be indistinguishable by value from one that did not,
    // which is why the claim is identity.
    assert(adopted.evm.schedule eq base.evm.schedule, "a document that names no price rebuilt the price list")

  it should "leave the precompile prices as the same value" in
    assert(
      adopted.evm.precompiles eq base.evm.precompiles,
      "a document that adds an operation rebuilt the precompile set"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "an operation added to the machine altered a facet the document does not name"
    )

  it should "leave the ordinary call at 0xf1 where it found it" in
    // The entry a reader is most likely to conflate this one with: the document
    // describes the new operation by naming that one.
    assert(
      adopted.evm.table.operationAt(0xf1) == base.evm.table.operationAt(0xf1),
      "adopting the read-only form moved the ordinary one"
    )

  "the operation this document adds" should "leave a callee that stores unable to" in
    // "Any attempts to make state-changing operations inside an execution
    // instance with STATIC set to true will instead throw an exception. These
    // operations include ... SSTORE". Run at this network's own rules rather
    // than in the machine, because the entry this document adds is what makes
    // the refusal reachable from a rule set at all.
    assert(
      run(asking)._2.world.storageAt(named, Word.One) == Word.Zero,
      "the callee stored, so the flag this document introduces did not reach it"
    )

  it should "have run that program to its end, or the case above pins nothing" in
    // The control the case above needs most, and it is not the one about the
    // ordinary call: an unset slot at the named account is also what a caller
    // that halted on an unknown byte leaves behind, since its own writes go back
    // with the callee's. The caller's trailing store is what tells the two
    // apart.
    assert(
      run(asking)._2.world.storageAt(runsAs, Word(BigInt(2))) == Word.One,
      "the caller never reached the end of its program, so the unset slot above says nothing about the flag"
    )

  it should "have let the same callee store where an ordinary call made it" in
    // The control, and the whole of what makes the case above about the flag:
    // the same callee, the same rules, the same budget, one more operand and a
    // different byte.
    assert(
      run(askingOrdinarily)._2.world.storageAt(named, Word.One) == EvmFixtures.word(42),
      "the callee never stored under an ordinary call either, so the case above measures nothing"
    )

  it should "read as a failed nesting rather than halting the invocation that made it" in
    // The proposal puts the exception inside the child, so a caller learns of it
    // from the zero and goes on running.
    assert(
      run(asking)._1.stack.peek(0) == Right(Word.Zero),
      "the refusal reached the caller rather than staying inside the invocation it was asked of"
    )

  it should "not have been runnable at all before the document was adopted" in
    // The second control, and the sharper one: at the preceding rules that byte
    // runs nothing, so the program cannot execute. Without it every case above
    // would hold for a build carrying the operation all along.
    assert(
      Interpreter.run(
        new Frame(EvmFixtures.message(transfersValue = true), Code(codeOf(asking)), BigInt(1000000)),
        EvmFixtures.environmentUnder(base.evm, new MapWorldState)
      ) == Right(Outcome.Halted(Halt.InvalidOpcode(0xfa))),
      "the byte this document introduces already ran something at the rules before it"
    )
