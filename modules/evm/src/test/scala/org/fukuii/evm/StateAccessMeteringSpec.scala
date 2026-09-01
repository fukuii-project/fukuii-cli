package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** What the machine charges for reaching an account or a slot, under each of the
  * two schemes a fork can put in force.
  *
  * ==Every figure is read off the fixture schedule, which holds a distinct value
  * in every field==
  *
  * A case comparing a charge against a real network's numbers cannot tell an
  * implementation that reads the schedule from one with the figure compiled in,
  * and cannot tell two fields apart wherever the network sets them alike. The
  * fixture's warm figure, its two cold figures and the settled prices of the
  * four repriced operations are all different numbers that no network uses.
  *
  * ==A charge is measured as what the frame did NOT spend==
  *
  * Each program runs with a known allowance and what it left is subtracted, so a
  * case names a difference rather than an absolute. That is what lets one
  * program be run under both schemes and the two answers compared -- the
  * comparison most cases here rest on, because an implementation ignoring the
  * rule answers the same under both.
  */
class StateAccessMeteringSpec extends AnyFlatSpec:

  // Held above every test registration: Scala 3's initialization checker reads a
  // val declared below one as read-before-init, and reports it against the first
  // test in the class rather than against the val.

  private val schedule = EvmFixtures.schedule

  /** The account whose code runs. */
  private val target: Address = EvmFixtures.address(0x22)

  /** An account the programs below reach, distinct from the one running. */
  private val other: Address = EvmFixtures.address(0x33)

  /** What a frame is given, comfortably above anything charged here. */
  private val allowance: BigInt = 1000000

  /** `PUSH20 <address>` -- the operand every account-reading operation takes. */
  private def pushing(address: Address): Seq[Int] =
    0x73 +: address.toBytes.toSeq.map(_ & 0xff)

  /** `PUSH1 n`. */
  private def pushingSmall(value: Int): Seq[Int] = Seq(0x60, value)

  /** Reaching `other` with `opcode`, discarding what it pushed. */
  private def reaching(opcode: Opcode): Seq[Int] = pushing(other) ++ Seq(opcode.code, Opcode.Pop.code)

  /** Reading slot `number` and discarding what it pushed. */
  private def loading(number: Int): Seq[Int] = pushingSmall(number) ++ Seq(Opcode.SLoad.code, Opcode.Pop.code)

  /** `PUSH1 value, PUSH1 slot, SSTORE` -- the operand order the machine pops in.
    */
  private def storing(value: Int, number: Int): Seq[Int] =
    pushingSmall(value) ++ pushingSmall(number) ++ Seq(Opcode.SStore.code)

  /** What one push and one pop cost, so a case can name an operation's own
    * charge rather than its program's total.
    */
  private val overhead: BigInt = schedule.veryLow + schedule.base

  /** The table under `metering`.
    *
    * The four operations this document reprices lose their figure under the
    * warm-and-cold scheme, and `EXTCODEHASH` has to be placed under both -- the
    * original instruction set does not carry it, so the fixture's table has no
    * entry for it at all and a program using it would meet an invalid byte.
    */
  private def tableUnder(metering: StateAccessMetering): OpcodeTable =
    val placed = EvmFixtures.rules.table.adding(Operation(Opcode.ExtCodeHash, Cost.Fixed(schedule.extCodeHash)))
    metering match
      case StateAccessMetering.Settled  => placed
      case StateAccessMetering.WarmCold =>
        placed
          .adding(Operation(Opcode.Balance, Cost.Computed))
          .adding(Operation(Opcode.ExtCodeSize, Cost.Computed))
          .adding(Operation(Opcode.ExtCodeHash, Cost.Computed))
          .adding(Operation(Opcode.SLoad, Cost.Computed))

  private def world(): EvmFixtures.MapWorldState =
    val built = new EvmFixtures.MapWorldState
    built.setBalance(other, EvmFixtures.word(1))
    built.setCode(other, EvmFixtures.bytesOf("0x6001"))
    built.setBalance(target, EvmFixtures.word(1000000))
    built

  /** Runs `program` under `metering`, answering what it spent.
    *
    * `warm` and `warmSlots` seed the frame the way whatever settles a
    * transaction would, which is the only route to the pre-warmed case from
    * inside the machine.
    *
    * An invocation that did not stop normally is a failure rather than a figure:
    * a frame that ran out of gas reports having spent its whole allowance, which
    * is a number every case here would go on comparing.
    */
  private def spent(
      metering: StateAccessMetering,
      program: Seq[Int],
      warm: Set[Address] = Set.empty,
      warmSlots: Set[(Address, Word)] = Set.empty
  ): BigInt =
    val rules = EvmFixtures.rules.copy(stateAccessMetering = metering, table = tableUnder(metering))
    val environment = EvmFixtures.environmentUnder(rules, world())
    val frame = new Frame(
      EvmFixtures.message(currentTarget = target, transfersValue = false),
      Code(Bytes.fromArray(program.map(_.toByte).toArray)),
      allowance,
      Set.empty,
      warm,
      warmSlots
    )
    Interpreter.run(frame, environment) match
      case Right(Outcome.Stopped(_, _)) => allowance - frame.gasLeft
      case other                        => fail("the program under test did not stop normally: " + other.toString)

  /** A call to `other` asking for no gas, sending nothing and naming no region,
    * with the answer discarded.
    *
    * The operands are pushed in the reverse of the order the operation pops
    * them, which is the output region, the input region, the value, the account
    * and then the request.
    */
  private val calling: Seq[Int] =
    pushingSmall(0) ++ pushingSmall(0) ++ pushingSmall(0) ++ pushingSmall(0) ++ pushingSmall(0) ++
      pushing(other) ++ pushingSmall(0) ++ Seq(Opcode.Call.code, Opcode.Pop.code)

  "BALANCE at an account this transaction has not reached" should "be charged the cold account figure" in
    assert(
      spent(StateAccessMetering.WarmCold, reaching(Opcode.Balance)) - overhead == schedule.coldAccountAccess,
      "a first reach is priced from coldAccountAccess and from no other field"
    )

  it should "be charged the warm figure where the frame was seeded with it" in
    assert(
      spent(StateAccessMetering.WarmCold, reaching(Opcode.Balance), warm = Set(other)) - overhead ==
        schedule.warmAccess,
      "an account named in the seed must not be charged as a first reach"
    )

  it should "be charged the warm figure the SECOND time within one frame" in
    // The accumulation, which the seed cannot demonstrate: two reaches at one
    // address total one cold plus one warm rather than two of either.
    assert(
      spent(StateAccessMetering.WarmCold, reaching(Opcode.Balance) ++ reaching(Opcode.Balance)) - overhead * 2 ==
        schedule.coldAccountAccess + schedule.warmAccess,
      "the operation did not record the reach it charged for"
    )

  it should "be charged the table's settled figure under the earlier scheme" in
    // The control every case above needs. Without it each of them holds for an
    // implementation that ignores the rule and always charges a cold figure.
    assert(
      spent(StateAccessMetering.Settled, reaching(Opcode.Balance)) - overhead == schedule.balance,
      "the earlier scheme charges the entry's figure, which is a field neither new figure equals"
    )

  "EXTCODESIZE" should "be charged the cold account figure on a first reach" in
    assert(
      spent(StateAccessMetering.WarmCold, reaching(Opcode.ExtCodeSize)) - overhead == schedule.coldAccountAccess,
      "an operation reaching an account is priced from the account figure whatever it reads there"
    )

  it should "be charged the table's settled figure under the earlier scheme" in
    assert(
      spent(StateAccessMetering.Settled, reaching(Opcode.ExtCodeSize)) - overhead == schedule.externalBase,
      "the earlier scheme charges externalBase, which is a different field from BALANCE's"
    )

  "EXTCODEHASH" should "be charged the cold account figure on a first reach" in
    assert(
      spent(StateAccessMetering.WarmCold, reaching(Opcode.ExtCodeHash)) - overhead == schedule.coldAccountAccess,
      "an operation reaching an account is priced from the account figure whatever it reads there"
    )

  it should "be charged the table's settled figure under the earlier scheme" in
    assert(
      spent(StateAccessMetering.Settled, reaching(Opcode.ExtCodeHash)) - overhead == schedule.extCodeHash,
      "the earlier scheme charges extCodeHash, which is a different field again"
    )

  "SLOAD at a slot this transaction has not reached" should "be charged the cold STORAGE figure" in
    // The storage figure and not the account one. The two are close in the field
    // -- 2100 against 2600 -- so an implementation reaching for one helper at
    // both would pass every account case above and be wrong here.
    assert(
      spent(StateAccessMetering.WarmCold, loading(1)) - overhead == schedule.coldStorageAccess,
      "a slot is priced from coldStorageAccess and an account from coldAccountAccess"
    )

  it should "be charged the warm figure the second time" in
    assert(
      spent(StateAccessMetering.WarmCold, loading(1) ++ loading(1)) - overhead * 2 ==
        schedule.coldStorageAccess + schedule.warmAccess,
      "the operation did not record the slot it charged for"
    )

  it should "be charged as a first reach at a DIFFERENT slot of the same account" in
    // What makes the set a set of slots rather than of accounts.
    assert(
      spent(StateAccessMetering.WarmCold, loading(1) ++ loading(2)) - overhead * 2 ==
        schedule.coldStorageAccess * 2,
      "reaching one slot must not warm another"
    )

  it should "be charged the warm figure where the frame was seeded with the pair" in
    assert(
      spent(StateAccessMetering.WarmCold, loading(1), warmSlots = Set((target, EvmFixtures.word(1)))) - overhead ==
        schedule.warmAccess,
      "a slot named in the seed must not be charged as a first reach"
    )

  it should "be charged as a first reach where the seed names that slot under ANOTHER account" in
    // The pair is the key. A seed keyed on the slot alone would warm this one.
    assert(
      spent(StateAccessMetering.WarmCold, loading(1), warmSlots = Set((other, EvmFixtures.word(1)))) - overhead ==
        schedule.coldStorageAccess,
      "the set is keyed on the account and the slot together"
    )

  it should "be charged the table's settled figure under the earlier scheme" in
    assert(
      spent(StateAccessMetering.Settled, loading(1)) - overhead == schedule.storageLoad,
      "the earlier scheme charges storageLoad, which this document leaves where it found it"
    )

  "an account reached by BALANCE" should "NOT warm that account's storage slots" in
    // The two sets are separate. What must not happen is a reach recorded in one
    // being read out of the other.
    assert(
      spent(StateAccessMetering.WarmCold, reaching(Opcode.Balance) ++ loading(1)) - overhead * 2 ==
        schedule.coldAccountAccess + schedule.coldStorageAccess,
      "reaching an account warmed a slot, or the other way about"
    )

  "SSTORE at a slot this transaction has not reached" should "pay the first-reach surcharge on top of its scheme" in
    // A prefix rather than a replacement: the difference between the two schemes
    // over one store is exactly the cold storage figure, every clause of the
    // metering scheme being the same under both.
    assert(
      spent(StateAccessMetering.WarmCold, storing(0x2a, 1)) - spent(StateAccessMetering.Settled, storing(0x2a, 1)) ==
        schedule.coldStorageAccess,
      "the surcharge replaced the metering scheme's charge instead of being added to it"
    )

  it should "pay it once where the same slot is stored to twice" in {
    val twice = storing(0x2a, 1) ++ storing(0x2b, 1)
    assert(
      spent(StateAccessMetering.WarmCold, twice) - spent(StateAccessMetering.Settled, twice) ==
        schedule.coldStorageAccess,
      "a second store to one slot paid the first-reach surcharge again"
    )
  }

  it should "pay nothing extra after a load of the same slot has paid it" in {
    // The document's own "applications that do SLOAD followed by SSTORE would
    // actually get cheaper". The load pays the surcharge and the store then
    // pays none, so the pair costs the store alone plus the load's operands --
    // one push and one pop -- and nothing more.
    val loadThenStore = loading(1) ++ storing(0x2a, 1)
    assert(
      spent(StateAccessMetering.WarmCold, loadThenStore) - spent(StateAccessMetering.WarmCold, storing(0x2a, 1)) ==
        overhead,
      "a store after a load of the same slot paid the first-reach surcharge a second time"
    )
  }

  "SELFDESTRUCT" should "pay the cold account figure for a beneficiary it has not reached" in {
    val destroying = pushing(other) ++ Seq(Opcode.SelfDestruct.code)
    assert(
      spent(StateAccessMetering.WarmCold, destroying) - spent(StateAccessMetering.Settled, destroying) ==
        schedule.coldAccountAccess,
      "the surcharge is added to what the operation already pays"
    )
  }

  it should "pay NOTHING extra for a beneficiary it has already reached" in {
    // The exception the document states in as many words: this operation does
    // not charge the warm figure for a repeat, which is where it differs from
    // every call form. An implementation reusing the call family's helper here
    // overcharges by the warm figure, and this is the only case that says so.
    val destroying = pushing(other) ++ Seq(Opcode.SelfDestruct.code)
    assert(
      spent(StateAccessMetering.WarmCold, destroying, warm = Set(other)) ==
        spent(StateAccessMetering.Settled, destroying),
      "a destruction to a warmed beneficiary was charged the warm figure"
    )
  }

  "CALL at an account this transaction has not reached" should "SUBSTITUTE the cold account figure for the call base" in
    // A substitution and not an addition, which is what both authorities do:
    // `ethereum/execution-specs` @ `20f7f6271` passes `access_gas_cost` where it
    // passed `GasCosts.OPCODE_CALL_BASE` in each call form's `extra_gas`
    // (`forks/berlin/vm/instructions/system.py`). An implementation adding the
    // two would answer callBase larger here.
    //
    // One case reaches all four forms: `messageCall` computes this term once,
    // from the address operand, and the form decides only what else is added.
    assert(
      spent(StateAccessMetering.WarmCold, calling) - spent(StateAccessMetering.Settled, calling) ==
        schedule.coldAccountAccess - schedule.callBase,
      "the call base survived beside the access charge, or the access charge did not replace it"
    )

  it should "substitute the WARM figure where the frame was seeded with the account" in
    assert(
      spent(StateAccessMetering.WarmCold, calling, warm = Set(other)) -
        spent(StateAccessMetering.Settled, calling) == schedule.warmAccess - schedule.callBase,
      "a call to a seeded account was charged as a first reach"
    )

  it should "leave the account it reached warm for what follows" in
    // The recording half. A call that charged the cold figure and recorded
    // nothing would leave the BALANCE after it paying cold a second time, and
    // every case above would still pass.
    assert(
      spent(StateAccessMetering.WarmCold, calling ++ reaching(Opcode.Balance)) -
        spent(StateAccessMetering.WarmCold, calling) - overhead == schedule.warmAccess,
      "the call did not record the account it charged a first reach for"
    )
