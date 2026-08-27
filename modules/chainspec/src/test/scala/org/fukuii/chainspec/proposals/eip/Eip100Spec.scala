package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{DifficultyAdjustment, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-100 changes, and the one member beside it that must not
  * move.
  *
  * ==Through [[Eip100.component]], because the wiring is what is untested==
  *
  * The delta is one assignment and is reachable on its own, so a spec calling
  * it directly passes with the component wired to nothing. What a network
  * adopts is the component.
  *
  * ==What is asserted here, and what is asserted elsewhere==
  *
  * That the selector moves, and that nothing else does. What the selector then
  * makes the mechanism compute is `org.fukuii.consensus.pow.EthashEngine`'s,
  * and it is already certified: `ethereum/tests DifficultyTests` states 18,598
  * cases across seven published fork keys, reaches this algorithm under five of
  * them, and files 2,254 of those under the key this network gives the upgrade
  * that adopts it. Restating any of that here would assert the harness rather
  * than the adoption.
  */
class Eip100Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.spuriousDragon

  private val adopted: UpgradeRules = base.adopting(Eip100.component)

  "adopting EIP-100" should "target difficulty by the algorithm that reads the parent's ommers" in
    assert(
      adopted.consensus.difficultyAdjustment == DifficultyAdjustment.Eip100,
      "the algorithm the proposal names is not the one adopting it produced"
    )

  it should "have targeted difficulty by the preceding algorithm before it was adopted" in
    // The control, stated as the specific earlier case rather than as an
    // inequality: the enumeration has three members, so a case asserting only
    // that the two differ would pass against a rule set that had never adopted
    // EIP-2 either.
    assert(
      base.consensus.difficultyAdjustment == DifficultyAdjustment.Eip2,
      "the preceding rules already targeted difficulty by the algorithm this document introduces"
    )

  it should "settle that selector and nothing else on the consensus facet" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as the named one failing to move.
    assert(
      adopted.consensus == base.consensus.copy(difficultyAdjustment = DifficultyAdjustment.Eip100),
      "the targeted rules differ from the earlier ones by something other than the algorithm"
    )

  it should "leave the exponential term exactly where it found it" in
    // The member a reader is most likely to conflate this document with,
    // because the two are adopted together on this network and only one of them
    // names the term. EIP-649 delays it; this document's specification is one
    // line of the adjustment formula and names no delay at all.
    assert(
      adopted.consensus.difficultyBombDelay == base.consensus.difficultyBombDelay,
      "a change to the difficulty adjustment moved the exponential term beside it"
    )

  it should "reach no facet outside consensus" in
    assert(
      (adopted.evm eq base.evm) && (adopted.execution eq base.execution) &&
        (adopted.admission eq base.admission),
      "a rule about how difficulty is targeted altered the machine, settlement or admission"
    )
