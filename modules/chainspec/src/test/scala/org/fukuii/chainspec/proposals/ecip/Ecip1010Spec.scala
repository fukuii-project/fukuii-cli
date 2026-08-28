package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{DifficultyBombPause, ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereumclassic
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting ECIP-1010 changes, and the two members beside it that must not
  * move.
  *
  * ==Through [[Ecip1010.component]], because the wiring is what is untested==
  *
  * The delta is one assignment and is reachable on its own, so a spec calling
  * it directly passes with the component wired to nothing. What a network
  * adopts is the component.
  *
  * ==Both heights are restated here from the document rather than read off it==
  *
  * The cases below write three million and five million out as literals, so
  * that a delta and its assertion cannot agree by sharing one wrong constant.
  * The document is `ethereumclassic/ECIPs` @ `f398567f4`,
  * `_specs/ecip-1010.md`, Final, whose `Constants` block gives
  * `pause_block = 3000000` and `cont_block = 5000000`.
  *
  * ==What this spec does NOT certify==
  *
  * That the arithmetic over the window is right.
  * `org.fukuii.consensus.pow.certification.ClassicDifficultyCorpus` does that,
  * against this chain's own vectors and against its own literals rather than
  * this record. The two are deliberately separate: that corpus would pass
  * unchanged if this component were never written, and these cases would pass
  * unchanged if the engine computed the window wrongly.
  */
class Ecip1010Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereumclassic.Upgrades.gasReprice

  private val adopted: UpgradeRules = base.adopting(Ecip1010.component)

  "adopting ECIP-1010" should "hold the exponential term's reference point over the window the document names" in
    assert(
      adopted.consensus.difficultyBombPause ==
        Some(DifficultyBombPause(pausedFrom = BigInt(3000000), continuesFrom = BigInt(5000000))),
      "the window the document names is not the one adopting it produced"
    )

  it should "have carried no window before it was adopted" in
    // The control. Absence is the answer a network without the rule gives, so
    // the earlier rules are checked for emptiness rather than for another pair
    // -- a case asserting only that the two differ would pass against any
    // window whatsoever.
    assert(
      base.consensus.difficultyBombPause.isEmpty,
      "the preceding rules already held the exponential term's reference point still"
    )

  it should "leave the exponential term's delay at nothing" in
    // The member a reader is most likely to conflate this document with,
    // because both concern the same term and one is expressed as a count of
    // blocks. This network answered the term with a window and never with a
    // delay, so a delay appearing here would be the other family's rule
    // wearing this one's name.
    assert(
      adopted.consensus.difficultyBombDelay == BigInt(0),
      "a window over the exponential term also held it back by a fixed count of blocks"
    )

  it should "settle that and nothing else on the consensus facet" in
    // Stated as the whole record rather than as spot checks, so a member
    // reached by accident fails as loudly as the named one failing to move.
    assert(
      adopted.consensus == base.consensus.copy(
        difficultyBombPause = Some(DifficultyBombPause(pausedFrom = BigInt(3000000), continuesFrom = BigInt(5000000)))
      ),
      "the paused rules differ from the earlier ones by something other than the window"
    )

  it should "reach no facet outside consensus" in
    // The check the first non-EIP series owes: a component that alters a facet
    // its own proposal does not name is the seam failing, and reference
    // equality is what makes that testable rather than merely intended.
    assert(
      (adopted.evm eq base.evm) && (adopted.execution eq base.execution) &&
        (adopted.admission eq base.admission),
      "a rule about the exponential difficulty term altered the machine, settlement or admission"
    )

  it should "be recorded under its own series rather than by its number alone" in
    // 1010 is a document in both series at once, and the other one settles
    // nothing about difficulty: EIP-1010 is Uniformity Between two addresses,
    // Stagnant, at ethereum/EIPs @ dbfa6bee8. A component list holding bare
    // integers could not tell them apart.
    assert(
      adopted.components.contains(ProposalId.Ecip(1010)) &&
        !adopted.components.contains(ProposalId.Eip(1010)),
      "the series did not travel with the number, so this adoption is indistinguishable from another document's"
    )
