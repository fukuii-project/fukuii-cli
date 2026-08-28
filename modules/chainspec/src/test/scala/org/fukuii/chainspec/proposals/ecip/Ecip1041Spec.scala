package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{DifficultyBombPause, ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereumclassic
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting ECIP-1041 changes, and the earlier rule it must leave alone.
  *
  * ==Through [[Ecip1041.component]], because the wiring is what is untested==
  *
  * The delta is one assignment and is reachable on its own, so a spec calling
  * it directly passes with the component wired to nothing. What a network
  * adopts is the component.
  *
  * ==The height is restated here from the document rather than read off it==
  *
  * The cases below write five million nine hundred thousand out as a literal,
  * so that a delta and its assertion cannot agree by sharing one wrong
  * constant. The document is `ethereumclassic/ECIPs` @ `8dda72c24`,
  * `_specs/ecip-1041.md`, Final, whose abstract proposes removal *"at block
  * 5,900,000"*.
  *
  * ==The composition case is the one this document owes and ECIP-1010's did
  * not==
  *
  * Two documents settle one term, and the later defers to the earlier below
  * its own height rather than replacing it. So the case that matters here is
  * not what this delta writes but what it leaves standing, which is the
  * opposite shape from [[Ecip1010Spec]]'s -- there the earlier rules had to be
  * shown EMPTY before the adoption, here the later rules have to be shown to
  * still carry what they arrived with.
  *
  * ==What this spec does NOT certify==
  *
  * That the composition reaches an answer.
  * `org.fukuii.consensus.pow.ClassicUpgradeDifficultySpec` does that, driving
  * the engine with the composed rule set at heights either side of the
  * removal; these cases would pass unchanged if nothing ever read either
  * member.
  */
class Ecip1041Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereumclassic.Upgrades.gotham

  private val adopted: UpgradeRules = base.adopting(Ecip1041.component)

  /** The window the rules being built on already carry, restated rather than
    * read back, so that the composition case cannot be satisfied by whatever
    * happens to be there.
    */
  private val window: DifficultyBombPause =
    DifficultyBombPause(pausedFrom = BigInt(3000000), continuesFrom = BigInt(5000000))

  "adopting ECIP-1041" should "stop the exponential term at the height the document names" in
    assert(
      adopted.consensus.difficultyBombRemovedFrom == Some(BigInt(5900000)),
      "the height the document names is not the one adopting it produced"
    )

  it should "have removed nothing before it was adopted" in
    // The control. Absence is the answer a network without the rule gives, so
    // the earlier rules are checked for emptiness rather than for another
    // height -- a case asserting only that the two differ would pass against
    // any height whatsoever.
    assert(
      base.consensus.difficultyBombRemovedFrom.isEmpty,
      "the preceding rules already stopped computing the exponential term"
    )

  it should "leave ECIP-1010's window exactly as it found it" in
    // The composition, which is what this document's own implementation asks
    // for: its second branch defers to PREVIOUS_FORMULA, and its specification
    // names that formula as the window paused "from block 3,000,000 to block
    // 5,000,000". A delta clearing it here would have this network repeal a
    // proposal its own successor cites as still answering below the removal.
    assert(
      adopted.consensus.difficultyBombPause == Some(window),
      "adopting the removal disturbed the window the removal defers to"
    )

  it should "have found that window already in force" in
    // The control the case above needs. Without it that assertion would hold
    // against a delta that WROTE the window rather than preserving it, and
    // against rules that never had one.
    assert(
      base.consensus.difficultyBombPause == Some(window),
      "the rules this composes over do not carry the window, so preserving it asserts nothing"
    )

  it should "leave the graduated adjustment in force too" in
    // The third member of the composition, and the one furthest from this
    // document's subject: EIP-2's rule arrived 4,750,000 blocks earlier and
    // this document restates it in the closing line of its own implementation
    // block. Restating is not adopting, and neither is it repealing.
    assert(
      adopted.consensus.difficultyAdjustment == base.consensus.difficultyAdjustment,
      "a document that restates the adjustment it inherits changed it"
    )

  it should "leave the exponential term's delay at nothing" in
    // The member a reader is most likely to conflate this document with,
    // because a term absent above a height reads as a term held back for ever.
    // No delay states it: one large enough to floor the term at the removal
    // floors it at every lower height too, where this network answers a term.
    assert(
      adopted.consensus.difficultyBombDelay == BigInt(0),
      "a removal was spelled as a delay, which no pair of heights either side of the boundary satisfies"
    )

  it should "settle that and nothing else on the consensus facet" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as the named one failing to move.
    assert(
      adopted.consensus == base.consensus.copy(difficultyBombRemovedFrom = Some(BigInt(5900000))),
      "the rules that stop computing the term differ from the earlier ones by something other than the removal"
    )

  it should "reach no facet outside consensus" in
    // A component that alters a facet its own proposal does not name is the
    // seam failing, and reference equality is what makes that testable rather
    // than merely intended.
    assert(
      (adopted.evm eq base.evm) && (adopted.execution eq base.execution) &&
        (adopted.admission eq base.admission),
      "a rule about the exponential difficulty term altered the machine, settlement or admission"
    )

  it should "be recorded under its own series rather than by its number alone" in
    // The discriminator the package is filed on, asserted here without a
    // collision to point at: what makes a component list readable is the
    // series travelling with the number, and a case written only where the
    // other series happens to hold that number today would go missing the
    // moment it stopped.
    assert(
      adopted.components.contains(ProposalId.Ecip(1041)) &&
        !adopted.components.contains(ProposalId.Eip(1041)),
      "the series did not travel with the number, so this adoption is indistinguishable from another document's"
    )
