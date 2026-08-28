package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereumclassic
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting ECIP-1017 changes, which is the record and nothing else.
  *
  * ==Through [[Ecip1017.component]], because the wiring is what is untested==
  *
  * There is no delta to call directly here, so the component is the whole of
  * what a spec can reach. What a network adopts is the component.
  *
  * ==A component that writes nothing needs its cases chosen more carefully, not
  * less==
  *
  * Every assertion below has to fail against some implementation somebody might
  * plausibly write. Two shapes are guarded: a delta given a body -- caught by
  * comparing the facets by REFERENCE, since a body producing an equal copy
  * passes a value comparison -- and the emission amount being reduced here,
  * which is the reading of this document a rule set would express if it
  * expressed one at all.
  *
  * ==What this spec does NOT certify==
  *
  * The era ladder itself. `org.fukuii.consensus.pow.EmissionCorpus` and
  * `org.fukuii.consensus.pow.certification.OmmerPaymentCorpus` do that against
  * this chain's own vectors, and they would pass unchanged if this component
  * were never written.
  */
class Ecip1017Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereumclassic.Upgrades.dieHard

  private val adopted: UpgradeRules = base.adopting(Ecip1017.component)

  "adopting ECIP-1017" should "record the document under its own series" in
    assert(
      adopted.components.contains(ProposalId.Ecip(1017)),
      "the rules a network reaches by adopting the emission ladder do not record having adopted it"
    )

  it should "not have been recorded before it was adopted" in
    // The control. Without it the case above would pass against a component
    // wired to nothing, because the earlier rules would already carry the id.
    assert(
      !base.components.contains(ProposalId.Ecip(1017)),
      "the preceding rules already recorded a document this one is what adopts"
    )

  it should "reach no facet at all" in
    // Reference equality rather than value equality, and that is the whole
    // point of the case: a delta that copied a facet and changed nothing in it
    // would pass a value comparison. The era ladder is computed by the engine
    // from an era length, so there is no member here for this document to
    // settle, and a facet arriving as a copy means one was settled.
    assert(
      (adopted.evm eq base.evm) && (adopted.execution eq base.execution) &&
        (adopted.admission eq base.admission) && (adopted.consensus eq base.consensus),
      "adopting a document whose arithmetic lives in the engine altered a rule set facet"
    )

  it should "leave the emission at the amount its own first era pays" in
    // The likeliest wrong implementation, because the document is about the
    // reward stepping down and a rule set does hold a reward. It is the base
    // the ladder reduces rather than a figure this document replaces: the first
    // era pays exactly it, so a delta reducing it here would step the emission
    // down one era early and every era after.
    assert(
      adopted.consensus.blockReward == base.consensus.blockReward,
      "adopting the ladder changed the amount the ladder's own first era pays"
    )
