package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.StorageMetering
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-1283 changes, and the far larger set it must leave alone.
  *
  * The nine clauses and the seven figures are certified against the document's
  * own seventeen published cases in [[Eip1283PropSpec]]. This spec covers the
  * one thing that file cannot: that the SWITCH is what moved, and that nothing
  * else did.
  */
class Eip1283Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.byzantium

  private val adopted: UpgradeRules = base.adopting(Eip1283.component)

  "adopting EIP-1283" should "put the net scheme in force" in
    assert(adopted.evm.storageMetering == StorageMetering.Net, "the document replaces the whole SSTORE charge")

  it should "have run the legacy scheme before it was adopted" in
    // The control, as the specific earlier case rather than as "not Net".
    assert(base.evm.storageMetering == StorageMetering.Legacy, "every fork below this one prices storage the old way")

  it should "settle that switch and nothing else in the machine" in
    // The seven figures are already in the schedule at every fork -- inert
    // until this switch reads them -- so adopting this must NOT rebuild the
    // schedule. A delta that did would be indistinguishable by value and would
    // hide a price moving.
    assert(
      adopted.evm == base.evm.copy(storageMetering = StorageMetering.Net),
      "the adopting rules differ from the earlier ones by something other than the switch"
    )

  it should "leave the table untouched" in
    // SSTORE is priced from its operands, so this document moves no entry. A
    // reader expecting a table change is expecting the wrong shape.
    assert(adopted.evm.table eq base.evm.table, "a scheme change must not reach the instruction table")

  it should "leave the schedule as the same value, not an equal copy" in
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "the figures were already there; adopting reads them, it does not write them"
    )

  it should "record itself in the component list" in
    assert(
      adopted.components.contains(org.fukuii.chainspec.ProposalId.Eip(1283)),
      "the journal must record what was adopted"
    )
