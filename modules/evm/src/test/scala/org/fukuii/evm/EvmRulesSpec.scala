package org.fukuii.evm

import org.fukuii.bytes.UInt64
import org.scalatest.flatspec.AnyFlatSpec

/** The fork seam's own properties, and the comparability the record now carries.
  *
  * ==Deltas written HERE, never taken from a chain configuration==
  *
  * The deltas below change one field each and are made up for the purpose. That
  * is deliberate and it is what makes this a machine spec: what a real proposal
  * does is a chain configuration's claim and is certified where that
  * configuration lives, while what `applying` does with ANY delta is the
  * machine's and is certified here. A spec that reached for a real proposal
  * would be asserting both at once and could not fail for only one reason.
  *
  * **The load-bearing claim is that a delta leaves alone what it does not
  * name.** A delta that rebuilds what it was asked to leave alone is the seam
  * failing, and it fails quietly: the fork it produces is still correct, and the
  * next fork derived from the same rules is not.
  */
class EvmRulesSpec extends AnyFlatSpec:

  private val base: EvmRules = EvmFixtures.rules

  /** Changes one price and nothing else, so what survives is observable. */
  private val repricing: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionBase = BigInt(53000)))

  /** Settles one behavior and nothing else. */
  private val depositRule: Proposal = _.copy(codeDepositMustSucceed = true)

  private val secondRepricing: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionBase = EvmFixtures.schedule.transactionBase))

  "the rules a spec starts from" should "leave a creation that cannot pay for its code holding no code rather than failing" in
    // Pinned here so a delta that reverses it has something to reverse. Rules
    // that already carried the later behavior would make that delta
    // unobservable.
    assert(!base.codeDepositMustSucceed)

  "applying" should "change nothing when given no proposal" in
    assert(base.applying() == base)

  it should "leave every field the proposal does not name as the same value, not an equal copy" in {
    // `eq`, not `==`. Both members now carry value equality, so an equal copy
    // WOULD pass a value comparison -- and that is exactly the state this
    // asserts against: the seam is meant to hand back what it was given, not to
    // rebuild something indistinguishable from it.
    val changed = base.applying(repricing)
    assert(
      (changed.table eq base.table) && (changed.precompiles eq base.precompiles),
      "a repricing rebuilt the table or the precompiles"
    )
  }

  it should "leave the rules it was applied to unaltered" in {
    // A starting configuration is a shared value every fork derives from, so a
    // delta that mutated it would corrupt every other fork rather than only its
    // own.
    val _ = base.applying(repricing, depositRule)
    assert(
      base.schedule.transactionBase == EvmFixtures.schedule.transactionBase && !base.codeDepositMustSucceed,
      "applying a proposal altered the rules it derived from"
    )
  }

  it should "carry a behavior change no table and no schedule could express" in
    assert(base.applying(depositRule).codeDepositMustSucceed)

  it should "leave the schedule alone when the proposal names only a behavior" in
    assert(base.applying(depositRule).schedule eq base.schedule)

  it should "apply proposals in the order given" in
    // Two proposals touching one price compose to whichever ran last, so the
    // order is the caller's to state rather than the seam's to sort.
    assert(base.applying(repricing, secondRepricing).schedule.transactionBase == EvmFixtures.schedule.transactionBase)

  it should "compose to a different result when that order is reversed" in
    assert(base.applying(secondRepricing, repricing).schedule.transactionBase == BigInt(53000))

  "an uncapped forwarding rule" should "hand over the whole of a request larger than what the caller has left" in
    // The trap the two-argument shape exists to avoid, pinned as a claim so it
    // cannot be simplified away. A rule written to take only what remains would
    // return the smaller of the two here, and a caller asking for more than it
    // holds would quietly succeed with less rather than running out of gas --
    // a different state root at every fork before a cap arrives.
    assert(
      GasForwarding.Whole.forward(BigInt(100), BigInt(1000)) == BigInt(1000),
      "the uncapped rule compared the request against what remains, which only a cap may do"
    )

  "a fractional cap" should "keep back one part of what remains" in
    assert(
      GasForwarding.AllButOneSixtyFourth.forward(BigInt(6400), BigInt(1000000)) == BigInt(6300),
      "a request above the cap is met with the cap"
    )

  it should "hand over what was asked for where that is under the cap" in
    assert(
      GasForwarding.AllButOneSixtyFourth.forward(BigInt(6400), BigInt(50)) == BigInt(50),
      "the cap is a ceiling on the request rather than a replacement for it"
    )

  it should "keep back nothing where fewer units remain than the divisor" in
    // The division floors, so the fraction of a small remainder is nothing and
    // the caller keeps nothing back. Pinned because rounding the other way would
    // be invisible on every figure large enough to look realistic.
    assert(
      GasForwarding.AllButOneSixtyFourth.forward(BigInt(63), BigInt(1000)) == BigInt(63),
      "the share kept back is floored, not rounded"
    )

  it should "compare by which rule it is, not by identity" in
    // The whole reason this member is data rather than a function. Two lambdas
    // computing the same thing are not answerably equal, so while it was one no
    // caller could ask whether two networks forwarded gas alike.
    assert(
      GasForwarding.AllButOneSixtyFourth != GasForwarding.Whole &&
        GasForwarding.AllButOneSixtyFourth == GasForwarding.AllButOneSixtyFourth,
      "a forwarding rule did not compare by what it does"
    )

  "two rule sets" should "compare equal when they were built separately from the same parts" in {
    // The capability the whole record was made comparable for: *do these two
    // networks run the same rules* has to be answerable without the answer
    // depending on whether a caller built one value or two. Every member is
    // rebuilt here rather than shared, so nothing in this comparison is
    // reference identity.
    val rebuilt = EvmRules(
      table = OpcodeTable.original(EvmFixtures.schedule),
      schedule = EvmFixtures.schedule,
      precompiles = EvmFixtures.precompiles,
      gasForwarded = GasForwarding.Whole,
      codeDepositMustSucceed = false,
      maxCodeSize = None,
      createdAccountNonce = UInt64.Zero
    )
    assert(rebuilt == base, "two identical configurations built separately compared as different rules")
  }

  it should "compare unequal when a single price differs" in
    // The other direction, without which the claim above would be satisfied by
    // an equality that answered true for everything.
    assert(
      base.applying(repricing) != base,
      "a rule set differing by one price compared equal"
    )

  it should "give two separately built tables one hash as well as one equality" in {
    // `equals` and `hashCode` are hand-written on the table and nothing else
    // pins them together. `EvmRules` is a case class whose generated hash
    // chains through this member, so an edit touching one override and not the
    // other breaks every Set and Map keyed on a rule set -- silently, because
    // equality would still answer correctly.
    val rebuilt = OpcodeTable.original(EvmFixtures.schedule)
    assert(
      (rebuilt ne base.table) && rebuilt == base.table && rebuilt.hashCode == base.table.hashCode,
      "two tables that compare equal hash differently, so the equality contract is broken"
    )
  }

  it should "give two separately built precompile sets one hash as well as one equality" in {
    // The other hand-written pair, for the same reason and with the same
    // failure mode. Rebuilt rather than reused: the fixture's set is the very
    // one this rule set holds, so comparing it against itself would pass
    // whatever either override did.
    val rebuilt = PrecompileSet.Empty
      .adding(PrecompileSet.EcRecover, Precompile.EcRecover(EvmFixtures.schedule.precompileEcRecover))
      .adding(
        PrecompileSet.Sha256,
        Precompile.Sha256(EvmFixtures.schedule.precompileSha256Base, EvmFixtures.schedule.precompileSha256PerWord)
      )
      .adding(
        PrecompileSet.Ripemd160,
        Precompile.Ripemd160(
          EvmFixtures.schedule.precompileRipemd160Base,
          EvmFixtures.schedule.precompileRipemd160PerWord
        )
      )
      .adding(
        PrecompileSet.Identity,
        Precompile.Identity(EvmFixtures.schedule.precompileIdentityBase, EvmFixtures.schedule.precompileIdentityPerWord)
      )
    assert(
      (rebuilt ne base.precompiles) && rebuilt == base.precompiles &&
        rebuilt.hashCode == base.precompiles.hashCode,
      "two precompile sets that compare equal hash differently, so the equality contract is broken"
    )
  }

  it should "compare unequal when a single operation differs" in
    // The table is one of the two members that carried no equality of its own,
    // so this is the case that would have passed for the wrong reason before.
    assert(
      base.copy(table = base.table.removing(Opcode.Add)) != base,
      "a rule set missing an operation compared equal"
    )

  it should "compare unequal when a single precompile is priced differently" in
    // The other such member, and the one whose equality rests on the natives
    // themselves comparing by value.
    assert(
      base.copy(
        precompiles = base.precompiles.adding(PrecompileSet.Identity, Precompile.Identity(BigInt(1), BigInt(1)))
      ) != base,
      "a rule set with a differently-priced native compared equal"
    )
