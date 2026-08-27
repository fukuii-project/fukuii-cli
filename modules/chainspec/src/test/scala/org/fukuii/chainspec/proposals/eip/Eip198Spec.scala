package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Precompile, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-198 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip198.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The address and the price below are read from the proposal's own text. What
  * the native COMPUTES -- the padding, the two settled answers, the arbitrary
  * precision of the charge -- is the machine's, and `org.fukuii.evm`'s
  * `PrecompileSpec` and `PrecompilePropSpec` certify it there against other
  * implementations' published vectors.
  */
class Eip198Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip198.component)

  "adopting EIP-198" should "place a native at 0x05" in
    // "At address 0x00......05, add a precompile", ethereum/EIPs @ 9e393a79,
    // EIPS/eip-198.md, Final.
    assert(
      adopted.evm.precompiles.at(PrecompileSet.ModExp).isDefined,
      "0x05 still runs whatever code the account holds"
    )

  it should "build it with the divisor the schedule states" in
    // The document gives GQUADDIVISOR under # Parameters and the network's
    // schedule is what holds it, so the assertion is that the entry was built
    // from the schedule rather than that it was built with 20. A delta naming
    // the figure would pass a check written the other way round and would then
    // ignore a network that stated a different one.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.ModExp)
        .contains(Precompile.ModExp(base.evm.schedule.precompileModExpDivisor)),
      "0x05 answers something other than this document's native, or at some other price"
    )

  it should "have run nothing at that address before it was adopted" in
    // The control. Without it the two cases above pass against a set that
    // already carried the entry, and an absent entry is what every height
    // before this document runs.
    assert(
      base.evm.precompiles.at(PrecompileSet.ModExp).isEmpty,
      "the preceding set already answered at this address"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.precompiles.size == base.evm.precompiles.size + 1,
      "adopting a document that adds one native moved the set by some other amount"
    )

  it should "leave the four the network placed at its genesis where it found them" in
    assert(
      Seq(PrecompileSet.EcRecover, PrecompileSet.Sha256, PrecompileSet.Ripemd160, PrecompileSet.Identity)
        .forall(address => adopted.evm.precompiles.at(address) == base.evm.precompiles.at(address)),
      "adding a native at a new address disturbed one already placed"
    )

  it should "settle that entry and nothing else in the machine" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as the named one failing to move.
    // The operation table is the one a reader most expects to move and is the
    // one this document cannot reach: a native has no byte in the instruction
    // set.
    assert(
      adopted.evm == base.evm.copy(precompiles =
        base.evm.precompiles
          .adding(PrecompileSet.ModExp, Precompile.ModExp(base.evm.schedule.precompileModExpDivisor))
      ),
      "the adopting rules differ from the earlier ones by something other than the one entry"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    // The divisor is a member the schedule already holds, so this document adds
    // no price. A delta that rebuilt the record would be indistinguishable by
    // value from one that did not, which is why the claim is identity.
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document whose price is one the schedule already holds rebuilt it"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a native added to the machine altered a facet the document does not name"
    )
