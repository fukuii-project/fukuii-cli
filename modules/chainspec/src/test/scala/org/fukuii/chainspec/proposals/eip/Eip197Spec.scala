package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.{Precompile, PrecompileSet}
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-197 changes, and the far larger set it must leave alone.
  *
  * ==Through [[Eip197.component]], because the wiring is what is untested==
  *
  * The delta is reachable on its own and a spec calling it directly passes with
  * the component wired to nothing. What a network adopts is the component.
  *
  * ==Read with EIP-196 and adopted apart from it, which is what is asserted
  * here==
  *
  * The two documents name each other and neither declares the other a
  * dependency, so this one has to be adoptable alone. That is a claim about the
  * composition rather than about either native, and it is why the base below is
  * the rule set with neither adopted rather than the one with the pair.
  */
class Eip197Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip197.component)

  private val schedule = base.evm.schedule

  private def entry(base: BigInt, perPoint: BigInt): Precompile =
    Precompile.AltBn128PairingCheck(base, perPoint)

  "adopting EIP-197" should "place a native at 0x08" in
    // "Address: 0x8", ethereum/EIPs @ dbfa6bee83, EIPS/eip-197.md, Final.
    assert(
      adopted.evm.precompiles.at(PrecompileSet.AltBn128PairingCheck).isDefined,
      "0x08 still runs whatever code the account holds"
    )

  it should "build it with both prices the schedule states" in
    // The document gives the charge as 80,000 per point over a base of
    // 100,000, and the network's schedule is what holds the two figures.
    assert(
      adopted.evm.precompiles
        .at(PrecompileSet.AltBn128PairingCheck)
        .contains(entry(schedule.precompileAltBn128PairingBase, schedule.precompileAltBn128PairingPerPoint)),
      "0x08 answers something other than this document's native, or at some other price"
    )

  it should "read both prices out of the rules it is applied to, in the right places" in {
    // Moved to DIFFERENT values, because one figure in both positions would
    // pass a delta that read the base and used it for the per-point charge as
    // well -- and that delta would price every real pairing wrongly while
    // pricing an empty input correctly.
    val elsewhere = base.copy(evm =
      base.evm.copy(schedule =
        schedule.copy(precompileAltBn128PairingBase = 17, precompileAltBn128PairingPerPoint = 19)
      )
    )
    assert(
      elsewhere
        .adopting(Eip197.component)
        .evm
        .precompiles
        .at(PrecompileSet.AltBn128PairingCheck)
        .contains(entry(BigInt(17), BigInt(19))),
      "a network stating its own prices got someone else's, or got one of its own twice"
    )
  }

  it should "have run nothing at that address before it was adopted" in
    assert(
      base.evm.precompiles.at(PrecompileSet.AltBn128PairingCheck).isEmpty,
      "the preceding set already answered at this address"
    )

  it should "add exactly one entry" in
    assert(
      adopted.evm.precompiles.size == base.evm.precompiles.size + 1,
      "adopting a document that adds one native moved the set by some other amount"
    )

  it should "be adoptable without the document it is read alongside" in
    // Neither document declares the other a dependency, so this one placing its
    // own entry while the other's two addresses stay empty is the claim.
    assert(
      adopted.evm.precompiles.at(PrecompileSet.AltBn128Add).isEmpty &&
        adopted.evm.precompiles.at(PrecompileSet.AltBn128Mul).isEmpty,
      "adopting one of the pair placed the other's natives too"
    )

  it should "compose with that document to the same set whichever is adopted first" in
    assert(
      base.adopting(Eip196.component, Eip197.component).evm ==
        base.adopting(Eip197.component, Eip196.component).evm,
      "two documents that name no field in common composed to different machines"
    )

  it should "settle that entry and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(precompiles =
        base.evm.precompiles.adding(
          PrecompileSet.AltBn128PairingCheck,
          entry(schedule.precompileAltBn128PairingBase, schedule.precompileAltBn128PairingPerPoint)
        )
      ),
      "the adopting rules differ from the earlier ones by something other than the one entry"
    )

  it should "leave the schedule as the same value, not an equal copy" in
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document whose prices are ones the schedule already holds rebuilt it"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a native added to the machine altered a facet the document does not name"
    )
