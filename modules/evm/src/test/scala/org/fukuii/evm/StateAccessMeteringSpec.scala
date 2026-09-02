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

  /** An account reached only by code a NESTED invocation runs, so that whether
    * the invocation that started it is charged a first reach answers whether a
    * nested reach survived.
    */
  private val elsewhere: Address = EvmFixtures.address(0x44)

  /** What a frame is given, comfortably above anything charged here. */
  private val allowance: BigInt = 1000000

  /** `PUSH20 <address>` -- the operand every account-reading operation takes. */
  private def pushing(address: Address): Seq[Int] =
    0x73 +: address.toBytes.toSeq.map(_ & 0xff)

  /** `PUSH1 n`. */
  private def pushingSmall(value: Int): Seq[Int] = Seq(0x60, value)

  /** `PUSH3 n`, which is what a forwarded request needs: an invocation asked to
    * pay for a first reach wants more gas than one byte can name, and a request
    * no caller can cover wants more than two. Every push width is priced alike,
    * so naming one operand through the widest costs a program nothing.
    */
  private def pushingLarge(value: Int): Seq[Int] =
    Seq(0x62, (value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff)

  /** Reaching `address` with `opcode`, discarding what it pushed. */
  private def reachingAt(address: Address, opcode: Opcode): Seq[Int] =
    pushing(address) ++ Seq(opcode.code, Opcode.Pop.code)

  /** Reaching `other` with `opcode`, discarding what it pushed. */
  private def reaching(opcode: Opcode): Seq[Int] = reachingAt(other, opcode)

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
    * warm-and-cold scheme, and `EXTCODEHASH` and `REVERT` have to be placed
    * under both -- the original instruction set carries neither, so the
    * fixture's table has no entry for either and a program using one would meet
    * an invalid byte. `REVERT` works out its price from the region it names, so
    * its entry carries no figure to be read.
    */
  private def tableUnder(metering: StateAccessMetering): OpcodeTable =
    val placed = EvmFixtures.rules.table
      .adding(Operation(Opcode.ExtCodeHash, Cost.Fixed(schedule.extCodeHash)))
      .adding(Operation(Opcode.Revert, Cost.Computed))
    metering match
      case StateAccessMetering.Settled  => placed
      case StateAccessMetering.WarmCold =>
        placed
          .adding(Operation(Opcode.Balance, Cost.Computed))
          .adding(Operation(Opcode.ExtCodeSize, Cost.Computed))
          .adding(Operation(Opcode.ExtCodeHash, Cost.Computed))
          .adding(Operation(Opcode.SLoad, Cost.Computed))

  /** The code `other` carries where a case does not care what a callee does:
    * it pushes, reaches nothing, and stops.
    */
  private val inert: Seq[Int] = pushingSmall(1)

  private def world(codeAtOther: Seq[Int]): EvmFixtures.MapWorldState =
    val built = new EvmFixtures.MapWorldState
    built.setBalance(other, EvmFixtures.word(1))
    built.setCode(other, Bytes.fromArray(codeAtOther.map(_.toByte).toArray))
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
      warmSlots: Set[(Address, Word)] = Set.empty,
      childCode: Seq[Int] = inert,
      forwarding: GasForwarding = EvmFixtures.rules.gasForwarded
  ): BigInt =
    val rules = EvmFixtures.rules
      .copy(stateAccessMetering = metering, table = tableUnder(metering), gasForwarded = forwarding)
    val environment = EvmFixtures.environmentUnder(rules, world(childCode))
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

  /** A call to `other` asking for `request` gas, sending nothing and naming no
    * region, with the answer discarded. `form` decides which of the two the
    * account named runs AS, which is what the slot cases below turn on.
    *
    * The operands are pushed in the reverse of the order the operation pops
    * them, which is the output region, the input region, the value, the account
    * and then the request.
    */
  private def callingWith(form: Opcode, request: Int): Seq[Int] =
    pushingSmall(0) ++ pushingSmall(0) ++ pushingSmall(0) ++ pushingSmall(0) ++ pushingSmall(0) ++
      pushing(other) ++ pushingLarge(request) ++ Seq(form.code, Opcode.Pop.code)

  /** A call to `other` asking for no gas, which is a callee that halts before
    * it can reach anything.
    */
  private val calling: Seq[Int] = callingWith(Opcode.Call, 0)

  /** What a call below forwards.
    *
    * Comfortably above what any callee here spends, so that a callee's reaches
    * are discarded by the ending under test rather than for want of gas -- and
    * comfortably below it, so that a callee handing back its remainder is
    * distinguishable from one that kept nothing. The case pinning that
    * distinction is what holds this figure to both bounds.
    */
  private val forwarded: Int = 20000

  /** A callee that reaches `elsewhere` and stops. */
  private val reachingThenStopping: Seq[Int] = reachingAt(elsewhere, Opcode.Balance)

  /** `PUSH1 0, PUSH1 0, REVERT` -- ending an invocation the failing way over an
    * empty region, which is charged for no memory.
    */
  private val reverting: Seq[Int] = pushingSmall(0) ++ pushingSmall(0) ++ Seq(Opcode.Revert.code)

  /** A callee that reaches `elsewhere` and then reverts. */
  private val reachingThenReverting: Seq[Int] = reachingThenStopping ++ reverting

  /** A callee that reaches slot 1 of whichever account it runs as, and stops. */
  private val loadingThenStopping: Seq[Int] = loading(1)

  /** A callee that reaches that slot and then reverts. */
  private val loadingThenReverting: Seq[Int] = loadingThenStopping ++ reverting

  /** A request no caller here can cover, so that a forwarding rule with a cap
    * hands over the cap rather than the request.
    */
  private val unaffordable: Int = 0xffffff

  /** A callee that meets a byte no table carries and so halts, which keeps
    * everything it was given rather than handing a remainder back. That is what
    * puts the amount forwarded into what the caller spent, where a case can
    * read it.
    */
  private val halting: Seq[Int] = Seq(0x0c)

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

  "an account a CALLEE reached" should "be charged the warm figure by its caller where the callee stopped" in
    // Every case above reaches what it reaches from the invocation being
    // measured, so none of them can tell whether a reach crosses back out of a
    // nested one. This account is named by no program the caller runs: the
    // caller pays a first reach for it only if what the callee recorded was
    // discarded on the way out.
    assert(
      spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.Call, forwarded) ++ reachingAt(elsewhere, Opcode.Balance),
        childCode = reachingThenStopping
      ) - spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.Call, forwarded),
        childCode = reachingThenStopping
      ) - overhead == schedule.warmAccess,
      "a reach made inside a nested invocation that stopped did not survive into the one that started it"
    )

  it should "be charged the COLD figure where the callee reverted" in
    // The exception the document states for a scope that fails: "if a scope
    // reverts, the access lists should be in the state they were in before that
    // scope was entered" (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2929.md`,
    // Final). Adding the caller's two set lines to the failing incorporation is
    // the symmetry that reads as a tidy-up and is a consensus change, and this
    // is the only case in the module that refuses it.
    assert(
      spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.Call, forwarded) ++ reachingAt(elsewhere, Opcode.Balance),
        childCode = reachingThenReverting
      ) - spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.Call, forwarded),
        childCode = reachingThenReverting
      ) - overhead == schedule.coldAccountAccess,
      "a reach made inside a nested invocation that reverted was kept by the one that started it"
    )

  "a callee that reverted" should "hand back what it did not spend" in
    // What keeps the two cases either side of this from passing on a callee
    // that never reached anything at all: one that ran out of gas keeps
    // everything forwarded and warms nothing, which answers the cold case
    // correctly for the wrong reason. It cannot answer this one, because a
    // program whose callee kept the whole request costs more than the request.
    assert(
      spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.Call, forwarded),
        childCode = reachingThenReverting
      ) < BigInt(forwarded),
      "the request forwarded did not cover the callee, whose reaches went for want of gas rather than by the revert"
    )

  "a slot a BORROWED callee reached" should "be charged the warm figure by its caller where the callee stopped" in
    // A borrowing form is what makes a nested slot observable at all: the set
    // is keyed on the account and the slot together, so the pair the callee
    // reached is the pair the caller goes on to read only where the callee ran
    // AS the account already running. Two forms do that, and this one is here
    // because the other takes one operand fewer -- it inherits its value
    // rather than popping one -- so the shared harness, which pushes a value,
    // would misalign its stack.
    assert(
      spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.CallCode, forwarded) ++ loading(1),
        childCode = loadingThenStopping
      ) - spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.CallCode, forwarded),
        childCode = loadingThenStopping
      ) - overhead == schedule.warmAccess,
      "a slot reached inside a nested invocation that stopped did not survive into the one that started it"
    )

  it should "be charged the COLD storage figure where the callee reverted" in
    assert(
      spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.CallCode, forwarded) ++ loading(1),
        childCode = loadingThenReverting
      ) - spent(
        StateAccessMetering.WarmCold,
        callingWith(Opcode.CallCode, forwarded),
        childCode = loadingThenReverting
      ) - overhead == schedule.coldStorageAccess,
      "a slot reached inside a nested invocation that reverted was kept by the one that started it"
    )

  "a call under a forwarding cap" should "settle the access charge BEFORE working out what it forwards" in {
    // Where the charge sits is observable only once a rule caps the amount, and
    // the document legislates the order rather than leaving it to be inferred:
    // the figure "is applied immediately (exactly like how `700` was charged
    // before this EIP), i.e: before calculating the `63/64ths` available for
    // entering the call" (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2929.md`,
    // Final).
    //
    // A callee that halts keeps everything handed to it, so what the caller
    // spent carries the amount forwarded. Subtracted first, the two runs have
    // different amounts to divide and differ by a sixty-fourth of the gap
    // between the two figures; applied after the division instead, both divide
    // the same amount and the runs differ by the WHOLE gap.
    val capped = callingWith(Opcode.Call, unaffordable)
    assert(
      spent(
        StateAccessMetering.WarmCold,
        capped,
        childCode = halting,
        forwarding = GasForwarding.AllButOneSixtyFourth
      ) -
        spent(
          StateAccessMetering.WarmCold,
          capped,
          warm = Set(other),
          childCode = halting,
          forwarding = GasForwarding.AllButOneSixtyFourth
        ) < schedule.coldAccountAccess - schedule.warmAccess,
      "the whole difference between the two figures reached the callee, so the charge was settled after the division"
    )
  }
