package org.fukuii.chainspec

import org.scalatest.flatspec.AnyFlatSpec

/** The series marker, and the collision that makes it load-bearing. */
class ProposalIdSpec extends AnyFlatSpec:

  /** 1015 is a real document in both series at once, and the two are unrelated
    * rules -- one proposes configurable issuance, the other adopts a repricing
    * of the operations that read account state.
    */
  "one number in two series" should "be two different proposals" in
    assert(
      ProposalId.Eip(1015) != ProposalId.Ecip(1015),
      "a component list holding bare integers could not tell these apart"
    )

  "a proposal" should "cite itself in the form its own document uses" in
    assert(
      ProposalId.Eip(150).show == "EIP-150" && ProposalId.Ecip(1017).show == "ECIP-1017",
      "the series is part of the citation, not decoration on it"
    )
