package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** What a creation pays for the length of the code it is initialized from, and
  * what it does when handed more than the rules admit.
  *
  * ==The two halves EIP-3860 puts inside the machine==
  *
  * Rules 3 and 4 of `ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-3860.md` (Final):
  * an abort where the length exceeds the bound, and an extra charge of
  * `INITCODE_WORD_COST * ceil(len(initcode) / 32)` deducted *"before the
  * calculation of the resulting contract address and the execution of
  * `initcode`"*. The other two rules are settled before the machine runs and are
  * `org.fukuii.execution.TransactionAdmission`'s and
  * `org.fukuii.execution.IntrinsicGas`'s.
  *
  * ==Prices are the fixture's and the rate is overridden per case==
  *
  * `EvmFixtures.schedule` holds the rate at zero, which is what a network below
  * this document charges, so the machine's existing create specs assert the
  * metering is inert there. Each case here states its own rate, distinct from
  * the hashing rate beside it, so a build that charged one where it meant the
  * other fails rather than agreeing.
  */
class InitcodeMeteringSpec extends AnyFlatSpec:

  private val creator: Address = EvmFixtures.address(0x22)

  private val rate: BigInt = BigInt(3)

  private val bound: Int = 64

  /** A rate that is not the hashing rate, so the two charges are separable.
    *
    * The salted form pays both over the same word count. Were they equal, a
    * build charging the hashing twice and the metering never would produce the
    * same figure as one charging each once.
    */
  private val schedule: GasSchedule = EvmFixtures.schedule.copy(initcodePerWord = rate)

  private def funded(): EvmFixtures.MapWorldState =
    val world = new EvmFixtures.MapWorldState
    world.setBalance(creator, Word(BigInt(1000000)))
    world

  /** The original table with the salted creation added, since that operation is
    * itself a later proposal's and is held out of the original set.
    */
  private val table: OpcodeTable =
    OpcodeTable.original(schedule).adding(Operation(Opcode.Create2, Cost.Computed))

  private def environmentWith(bounded: Option[Int]): Environment =
    EvmFixtures.environmentUnder(
      EvmFixtures.rules.copy(table = table, schedule = schedule, maxInitcodeSize = bounded),
      funded()
    )

  /** The same machine with the rate at zero, which is every height below the
    * document. What separates the two figures is the metering and nothing else.
    */
  private def environmentUnmetered(): Environment =
    EvmFixtures.environmentUnder(EvmFixtures.rules.copy(table = table), funded())

  /** Runs one create operation over a region of `size` bytes of memory.
    *
    * The operands are pushed in the order the operation pops them, which is
    * size, offset, endowment for the unsalted form -- so the program below
    * pushes them in reverse.
    */
  private def creating(
      where: Environment,
      gas: Int,
      size: Int,
      salted: Boolean
  ): (Frame, Either[Unsupported, Outcome]) =
    val push = Opcode.Push1.code
    val salt = if salted then Seq(push, 0x00) else Seq.empty
    val program =
      salt ++ Seq(push, size, push, 0x00, push, 0x00) ++
        Seq(if salted then Opcode.Create2.code else Opcode.Create.code)
    val frame = new Frame(
      EvmFixtures.message(currentTarget = creator, transfersValue = true),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      BigInt(gas)
    )
    (frame, Interpreter.run(frame, where))

  /** What one create of `size` bytes left, at the given bound. */
  private def leftAfter(size: Int, salted: Boolean, bounded: Option[Int], gas: Int = 200000): BigInt =
    val (frame, _) = creating(environmentWith(bounded), gas, size, salted)
    frame.gasLeft

  /** What the salted program spends that the unsalted one does not, before
    * either creation begins: one more operand pushed.
    */
  private val extraPush: BigInt = schedule.veryLow

  /** What one create of `size` bytes left with the rate held at zero. */
  private def leftUnmetered(size: Int, salted: Boolean): BigInt =
    val (frame, _) = creating(environmentUnmetered(), 200000, size, salted)
    frame.gasLeft

  "CREATE" should "charge the rate over each whole word of the region" in
    // Two words for 33 bytes, which is the rounding the document states as
    // `ceil(len / 32)`. Everything the creation itself then spends stays inside
    // the child, so what this frame kept is what the charge left it.
    assert(
      leftUnmetered(33, salted = false) - leftAfter(33, salted = false, bounded = None) == rate * 2,
      "two words at the stated rate, and nothing else moved"
    )

  it should "round a partial word up" in {
    val one = leftAfter(1, salted = false, bounded = None)
    val whole = leftAfter(32, salted = false, bounded = None)
    assert(one == whole, "one byte and a full word are both one word, so both pay the same rate once")
  }

  it should "charge nothing for an empty region" in
    assert(
      leftAfter(0, salted = false, bounded = None) == leftUnmetered(0, salted = false),
      "zero bytes is zero words, so the rate multiplies nothing"
    )

  "CREATE2" should "pay the metering as well, over the same words" in
    // Against its OWN unmetered run, so the two programs are identical and the
    // only difference is the rate. A build that let the hashing stand in for the
    // metering on the salted form passes every unsalted case above and fails
    // here.
    assert(
      leftUnmetered(33, salted = true) - leftAfter(33, salted = true, bounded = None) == rate * 2,
      "the salted form is metered by its length exactly as the unsalted one is"
    )

  it should "pay it ON TOP of the hashing rather than instead of it" in {
    // The document's own arithmetic: "after activation `2` for `CREATE` and
    // `6 + 2` for `CREATE2`". The two rates differ in this schedule, so a build
    // that substituted one for the other lands on a different figure. The salted
    // program pushes one operand more than the unsalted one, so that push is
    // taken out before the two are compared; what remains is the hashing.
    val betweenTheForms = leftAfter(33, salted = false, bounded = None) - leftAfter(33, salted = true, bounded = None)
    assert(
      betweenTheForms - extraPush == schedule.keccak256PerWord * 2,
      "the salted form pays the hashing rate as well, over the same two words"
    )
  }

  "a creation at the bound" should "run" in {
    // "`CREATE`/`CREATE2`/creation transaction with `len(initcode)` at
    // `MAX_INITCODE_SIZE`" -- the document's own test list, and the comparison
    // is strictly greater, so the bound itself is admitted.
    val (_, outcome) = creating(environmentWith(Some(bound)), 200000, bound, salted = false)
    assert(outcome.map(_.getClass) == Right(classOf[Outcome.Stopped]), "a region of exactly the bound is admitted")
  }

  "a creation one byte over the bound" should "abort as if it ran out of gas" in {
    // "instruction execution exceptionally aborts (as if it runs out of gas)",
    // which is a halt and not the zero the light checks push.
    val (_, outcome) = creating(environmentWith(Some(bound + 1)), 200000, bound + 2, salted = false)
    assert(outcome == Right(Outcome.Halted(Halt.OutOfGas)), "the abort is the document's, and it keeps nothing")
  }

  it should "keep nothing, where a refusal for balance hands the gas back" in {
    // The distinction the document draws by placing this among the early
    // out-of-gas checks, which "precede the later 'light' checks: call depth
    // and balance". A halt takes the whole frame; a light refusal pushes zero
    // and returns what it was given.
    val (frame, _) = creating(environmentWith(Some(bound)), 200000, bound + 1, salted = false)
    assert(frame.gasLeft == BigInt(0), "an exceptional abort leaves the frame with nothing")
  }

  "a creation over the bound under rules that bound nothing" should "run" in {
    // The negative control for the bound: the same region, the same rate, no
    // bound. A build that compared against a figure of its own rather than
    // against the rules would refuse this too.
    val (_, outcome) = creating(environmentWith(None), 200000, bound + 1, salted = false)
    assert(
      outcome.map(_.getClass) == Right(classOf[Outcome.Stopped]),
      "with no bound in force nothing is oversized, whatever the length"
    )
  }
