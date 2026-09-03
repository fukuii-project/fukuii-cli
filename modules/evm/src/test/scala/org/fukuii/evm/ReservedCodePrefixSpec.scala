package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** What a deployment does with code beginning with the byte a fork reserved.
  *
  * ==The pair of payloads is the instrument, and only the first byte differs==
  *
  * Each case deploys initialization code that writes one byte to memory and
  * returns it. The reserved payload returns `0xEF`; the control returns `0xFE`,
  * which is equally undefined as an instruction and equally never executed here,
  * so the two differ in nothing a rule may read except the byte under test. A
  * single payload could not separate "refuses the reserved byte" from "refuses
  * one-byte deployments".
  *
  * That shape is not invented here. `fukuii-project/fukuii-tests` uses the same
  * `0xFE`-against-`0xEF` pairing as its own isolator for this rule, for the same
  * reason.
  *
  * ==Expected behavior==
  *
  * `ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-3541.md` (Final): new contract
  * creation *"results in an exceptional abort if the code's first byte is
  * `0xEF`"*, and that abort *"behaves exactly the same as any other exceptional
  * abort ... all gas provided to a `CREATE*` or create transaction is
  * consumed."* Read against `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/london/vm/interpreter.py`, which guards the comparison behind a length
  * test -- so empty code is deployable and the guard is the specification's
  * rather than a defensive addition.
  */
class ReservedCodePrefixSpec extends AnyFlatSpec:

  private val target: Address = EvmFixtures.address(0x77)

  /** Rules that reserve the byte, and rules that reserve nothing. */
  private val reserving: EvmRules = EvmFixtures.rules.copy(reservedCodePrefix = Some(0xef))

  private val reservingNothing: EvmRules = EvmFixtures.rules.copy(reservedCodePrefix = None)

  /** Initialization code returning exactly one byte: PUSH1 b, PUSH1 0, MSTORE8,
    * PUSH1 1, PUSH1 0, RETURN.
    */
  private def returningOneByte(b: Int): Seq[Int] =
    Seq(0x60, b, 0x60, 0x00, 0x53, 0x60, 0x01, 0x60, 0x00, 0xf3)

  /** Initialization code returning nothing: PUSH1 0, PUSH1 0, RETURN. */
  private val returningNothing: Seq[Int] = Seq(0x60, 0x00, 0x60, 0x00, 0xf3)

  private def deployUnder(
      rules: EvmRules,
      initCode: Seq[Int]
  ): (Frame, Either[Unsupported, Outcome], JournaledWorldState) =
    // Read the deployed code back through the JOURNAL rather than through the
    // state under it. A deployment writes into the journal and the layer above
    // commits, so a read of the underlying state answers empty for a deployment
    // that succeeded -- which passes the refusal cases for the wrong reason and
    // fails the acceptance cases.
    val state = new JournaledWorldState(new EvmFixtures.MapWorldState)
    val environment = new Environment(
      state,
      EvmFixtures.blockHashAt,
      EvmFixtures.block,
      EvmFixtures.transaction,
      EvmFixtures.chainId,
      rules
    )
    val frame = new Frame(
      Message(EvmFixtures.address(0x11), target, None, Word.Zero, Bytes.Empty, transfersValue = true, isStatic = false),
      Code(Bytes.fromArray(initCode.map(_.toByte).toArray)),
      BigInt(100000)
    )
    (frame, Interpreter.deploy(frame, environment), state)

  "a deployment under rules reserving the byte" should "refuse code beginning with it" in {
    val (_, outcome, _) = deployUnder(reserving, returningOneByte(0xef))
    assert(
      outcome == Right(Outcome.Halted(Halt.InvalidContractPrefix)),
      "the document makes this an exceptional abort, named for the reason the specification declares"
    )
  }

  it should "store nothing for the refused deployment" in {
    val (_, _, state) = deployUnder(reserving, returningOneByte(0xef))
    assert(
      state.codeOf(target) == Bytes.Empty,
      "an aborted deployment must leave the destination as it found it"
    )
  }

  it should "consume every unit of gas it was given" in {
    val (frame, _, _) = deployUnder(reserving, returningOneByte(0xef))
    assert(
      frame.gasLeft == BigInt(0),
      "the document states all gas provided to the creation is consumed"
    )
  }

  it should "accept the control payload, which differs only in that byte" in {
    val (_, outcome, state) = deployUnder(reserving, returningOneByte(0xfe))
    assert(
      outcome.exists(_.isInstanceOf[Outcome.Stopped]) &&
        state.codeOf(target) == Bytes.fromArray(Array(0xfe.toByte)),
      "a byte the document does not reserve is stored, so the refusal is of the byte and not of the shape"
    )
  }

  it should "accept a deployment that returns nothing" in {
    // The specification guards its comparison behind a length test. Reading a
    // missing first byte as reserved would refuse every constructor that
    // deploys no code at all, which is legal at every fork.
    val (_, outcome, state) = deployUnder(reserving, returningNothing)
    assert(
      outcome.exists(_.isInstanceOf[Outcome.Stopped]) && state.codeOf(target) == Bytes.Empty,
      "empty code has no leading byte to reserve and is deployable"
    )
  }

  "a deployment under rules reserving nothing" should "store code beginning with that byte" in {
    // The fork-resolved half. Below the upgrade, 0xEF-prefixed code really was
    // deployable, and a machine hardcoding the refusal would rewrite history
    // for every earlier block.
    val (_, outcome, state) = deployUnder(reservingNothing, returningOneByte(0xef))
    assert(
      outcome.exists(_.isInstanceOf[Outcome.Stopped]) &&
        state.codeOf(target) == Bytes.fromArray(Array(0xef.toByte)),
      "a network that has not adopted the document must still store what it always stored"
    )
  }
