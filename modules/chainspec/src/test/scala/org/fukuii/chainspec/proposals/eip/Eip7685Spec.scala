package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.UpgradeRules
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-7685 changes at the rule-set layer, and what it cannot.
  *
  * The document defines a container and a commitment rather than any request, so
  * its rule-set contribution is a single header flag. Everything else it governs
  * -- which records exist, how the list is folded, what the commitment must
  * equal -- needs an executed body and belongs to the layer that has one.
  *
  * **So the absence assertions here carry as much as the presence one.** A
  * reader meeting only the flag could reasonably expect this document to have
  * added a transaction form or an operation; it adds neither, and a later
  * document that quietly attached one to this component would be changing what
  * a fork adopting the container alone gets.
  */
class Eip7685Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.cancun

  private val adopted: UpgradeRules = base.adopting(Eip7685.component)

  "adopting EIP-7685" should "require the header to commit to its block's requests" in
    assert(
      adopted.header.carriesRequestsHash,
      "a header at a fork with the container states the commitment"
    )

  it should "not have required it before it was adopted" in
    assert(
      !base.header.carriesRequestsHash,
      "the fork below states no such field, which is what makes its presence there a refusal"
    )

  it should "leave every other header rule alone" in
    // The flag is the whole delta, so the rest of the record must compare equal
    // -- including the blob schedule, which a fork adopting this document
    // alongside a widening would otherwise appear to have changed here.
    assert(
      adopted.header.copy(carriesRequestsHash = false) == base.header,
      "one flag moves and no other header rule does"
    )

  it should "add no transaction form" in
    assert(
      adopted.admission == base.admission,
      "the container is filled by records a block derives, never by a transaction a sender offers"
    )

  it should "add no operation" in
    // No request type is readable from inside the machine at this fork, so the
    // table is untouched. This is the assertion that would fail first if a
    // later document attached its own delta to this component.
    assert(
      adopted.evm.table == base.evm.table,
      "the container is invisible to the interpreter"
    )
