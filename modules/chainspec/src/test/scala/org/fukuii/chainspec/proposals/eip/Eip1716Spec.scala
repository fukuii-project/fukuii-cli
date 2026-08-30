package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.StorageMetering
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1716 changes -- a removal rather than an addition -- and
  * the property that makes it a removal rather than a third scheme.
  */
class Eip1716Spec extends AnyFlatSpec:

  private val byzantium: UpgradeRules = ethereum.Upgrades.byzantium

  private val withNetMetering: UpgradeRules = byzantium.adopting(Eip1283.component)

  private val withdrawn: UpgradeRules = withNetMetering.adopting(Eip1716.component)

  "adopting EIP-1716" should "put the legacy scheme back in force" in
    assert(
      withdrawn.evm.storageMetering == StorageMetering.Legacy,
      "the document removes EIP-1283 and restores what preceded it"
    )

  it should "RESTORE rather than introduce, leaving the machine as it was before EIP-1283" in
    // The property that makes this a withdrawal. Asserted against the rules
    // BELOW EIP-1283 rather than against a named case, so it would fail if the
    // withdrawal left anything of the net scheme behind, or introduced anything
    // of its own.
    assert(
      withdrawn.evm == byzantium.evm,
      "withdrawing the document left the machine somewhere other than where it was before the document"
    )

  it should "record BOTH the adoption and the withdrawal, in order" in
    // The journal is an ordered record of what was applied, not a set of what
    // is in force -- which is what lets one rule set state that a proposal was
    // adopted and then taken out. A set could not represent this at all.
    assert(
      withdrawn.components.endsWith(
        Vector(org.fukuii.chainspec.ProposalId.Eip(1283), org.fukuii.chainspec.ProposalId.Eip(1716))
      ),
      "the record must show the adoption followed by the withdrawal"
    )

  it should "be inert applied to rules that never adopted EIP-1283" in
    // Applying the withdrawal alone is not an error and changes nothing, which
    // is what makes the order-dependence at a shared activation a property of
    // the SCHEDULE rather than of these deltas.
    assert(
      byzantium.adopting(Eip1716.component).evm == byzantium.evm,
      "removing a scheme that was never in force must be a no-op on the machine"
    )
