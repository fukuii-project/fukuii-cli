package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereumclassic
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting ECIP-1039 changes, which is the record and nothing else.
  *
  * ==The case this spec exists for is that it is recorded SEPARATELY==
  *
  * ECIP-1039 arrives at the same height as ECIP-1017 and is about that
  * document's arithmetic, which makes folding the two into one component the
  * obvious economy. It would be wrong for the reason the document exists: two
  * implementations had read ECIP-1017 differently, so a rule set recording only
  * ECIP-1017 does not state which of the two readings it runs.
  *
  * **The fold has two halves and this spec catches one of them.** A single
  * component carrying the other document's number is caught here, by the case
  * below. A component that exists and is never adopted is invisible from here,
  * because these cases adopt it themselves;
  * `org.fukuii.chainspec.networks.ethereumclassic.UpgradesSpec` is what holds
  * the composition to its recorded list and catches that half.
  *
  * ==What this spec does NOT certify==
  *
  * Where the divisions actually fall.
  * `org.fukuii.consensus.pow.certification.OmmerPaymentCorpus` does that
  * against this chain's own vectors, and it would pass unchanged if this
  * component were never written.
  */
class Ecip1039Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereumclassic.Upgrades.dieHard

  private val adopted: UpgradeRules = base.adopting(Ecip1039.component)

  "adopting ECIP-1039" should "record the document under its own series" in
    assert(
      adopted.components.contains(ProposalId.Ecip(1039)),
      "the rules a network reaches by adopting the rounding specification do not record having adopted it"
    )

  it should "not have been recorded before it was adopted" in
    // The control, as in the sibling spec: without it the case above would pass
    // against a component wired to nothing.
    assert(
      !base.components.contains(ProposalId.Ecip(1039)),
      "the preceding rules already recorded a document this one is what adopts"
    )

  it should "be a component of its own rather than part of the one it specifies" in
    // The fold this document must survive. Adopting ECIP-1017 alone must not
    // record ECIP-1039, because the two are separate readings a client can
    // disagree about: one names the ladder, the other names where the ladder's
    // floor divisions fall, and a rule set carrying only the first cannot say
    // which arithmetic produced its rewards.
    assert(
      !base.adopting(Ecip1017.component).components.contains(ProposalId.Ecip(1039)),
      "the rounding specification was folded into the document it specifies, so adopting one records both"
    )

  it should "reach no facet at all" in
    // Reference equality rather than value equality, for the reason the sibling
    // spec gives. Where a division falls inside an expression is not a member
    // any facet has, so a facet arriving as a copy means one was settled.
    assert(
      (adopted.evm eq base.evm) && (adopted.execution eq base.execution) &&
        (adopted.admission eq base.admission) && (adopted.consensus eq base.consensus),
      "adopting a document about where a division falls altered a rule set facet"
    )
