package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Precompile, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-152 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip152.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The address and the per-round figure below are read from the proposal's own
  * text. What the native COMPUTES -- the width it fixes, the round count it
  * reads out of the first four bytes, and the refusal of a final-block byte
  * that is neither zero nor one -- is the machine's, and `org.fukuii.evm`'s
  * `Blake2fPrecompileSpec` certifies it there.
  *
  * ==The document that adds a native WITHOUT moving a price==
  *
  * Every other native in this build arrived alongside the figure it is priced
  * from. This one does not: `GFROUND` is already a member both networks state,
  * so the schedule must survive as the same value. That makes this the control
  * for [[Eip1108Spec]] in the same upgrade -- one document reaches the
  * precompile set and leaves the record alone, the other has to rewrite both.
  */
class Eip152Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.petersburg

  private val adopted: UpgradeRules = base.adopting(Eip152.component)

  "adopting EIP-152" should "place a native at 0x09" in
    // "We propose adding a precompiled contract at address `0x09` wrapping the
    // BLAKE2 `F` compression function", ethereum/EIPs @ dbfa6bee,
    // EIPS/eip-152.md, Final.
    assert(
      adopted.evm.precompiles.at(PrecompileSet.Blake2f).isDefined,
      "0x09 still runs whatever code the account holds"
    )

  it should "build it with the per-round figure the schedule states" in
    // The document gives GFROUND = 1 and the network's schedule is what holds
    // it, so the assertion is that the entry was built from the schedule rather
    // than that it was built with 1. A delta naming the figure would pass a
    // check written the other way round and would then ignore a network that
    // stated a different one.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.Blake2f)
        .contains(Precompile.Blake2f(base.evm.schedule.precompileBlake2fPerRound)),
      "0x09 answers something other than this document's native, or at some other price"
    )

  it should "read that figure out of the rules it is applied to" in {
    // The case above cannot see a delta naming 1, because 1 is what this
    // network's schedule states and the two agree. So the delta is applied a
    // second time to rules stating something else, where a literal answers the
    // network's figure and the schedule's reader answers the other one.
    val elsewhere = base.copy(evm = base.evm.copy(schedule = base.evm.schedule.copy(precompileBlake2fPerRound = 7)))
    assert(
      elsewhere
        .adopting(Eip152.component)
        .evm
        .precompiles
        .at(PrecompileSet.Blake2f)
        .contains(Precompile.Blake2f(BigInt(7))),
      "a network stating its own per-round figure got someone else's"
    )
  }

  it should "state that figure as one this network already held" in
    // The other half of the claim above: the schedule must carry a member for
    // this price BEFORE the document is adopted, or the entry would have to be
    // built from a literal and no network could ever state its own.
    assert(
      base.evm.schedule.precompileBlake2fPerRound == BigInt(1),
      "the schedule must state this network's per-round figure below the document that spends it"
    )

  it should "have run nothing at that address before it was adopted" in
    // The control. Without it the cases above pass against a set that already
    // carried the entry, and an absent entry is what every height before this
    // document runs.
    assert(
      base.evm.precompiles.at(PrecompileSet.Blake2f).isEmpty,
      "the preceding set already answered at this address"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.precompiles.size == base.evm.precompiles.size + 1,
      "adopting a document that adds one native moved the set by some other amount"
    )

  it should "leave every native already placed exactly as it found it" in
    assert(
      Seq(
        PrecompileSet.EcRecover,
        PrecompileSet.Sha256,
        PrecompileSet.Ripemd160,
        PrecompileSet.Identity,
        PrecompileSet.ModExp,
        PrecompileSet.AltBn128Add,
        PrecompileSet.AltBn128Mul,
        PrecompileSet.AltBn128PairingCheck
      ).forall(address => adopted.evm.precompiles.at(address) == base.evm.precompiles.at(address)),
      "adding a native at a new address disturbed one already placed"
    )

  it should "move no price at all" in
    // Stated as identity rather than as equality, which is the whole claim: the
    // figure this entry is built from is one the rules already held, so a delta
    // that rebuilt the record would be indistinguishable by value from one that
    // did not and would hide a price moving beside it.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document whose price is one the schedule already holds rebuilt it"
    )

  it should "leave the instruction table as the same value" in
    // A native answers at an address and has no byte in the instruction set, so
    // the table is the thing a reader most expects to move here and is the one
    // this document cannot reach.
    assert(adopted.evm.table eq base.evm.table, "a native added to the machine reached the instruction table")

  it should "settle that entry and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as the named one failing to move.
    assert(
      adopted.evm == base.evm.copy(precompiles =
        base.evm.precompiles
          .adding(PrecompileSet.Blake2f, Precompile.Blake2f(base.evm.schedule.precompileBlake2fPerRound))
      ),
      "the adopting rules differ from the earlier ones by something other than the one entry"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a native added to the machine altered a facet the document does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(152)), "the journal must record what was adopted")
