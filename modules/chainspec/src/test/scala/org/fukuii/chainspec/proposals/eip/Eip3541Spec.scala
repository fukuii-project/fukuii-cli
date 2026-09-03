package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.chainspec.networks.ethereum
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-3541 changes, and the far larger set it must leave alone.
  *
  * ==Here rather than with the machine, because these are the document's
  * claims==
  *
  * The byte below is read from the proposal's own text. What the machine DOES
  * with it -- refusing at every creation path, sparing empty code, and consuming
  * the whole of the gas -- is `org.fukuii.evm`'s, and `ReservedCodePrefixSpec`
  * certifies it there.
  *
  * ==The one case with no counterpart in the sibling specs==
  *
  * This document adds no operation, so the table must not move. Every other
  * machine-facing proposal in this upgrade adds an entry or a price; a component
  * written by copying one of them would reach the table, and the case below is
  * what refuses that.
  */
class Eip3541Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.berlin

  private val adopted: UpgradeRules = base.adopting(Eip3541.component)

  "adopting EIP-3541" should "reserve the byte the document names" in
    assert(
      adopted.evm.reservedCodePrefix.contains(0xef),
      "the document names 0xEF as the reserved leading byte"
    )

  it should "have reserved nothing before it was adopted" in
    assert(
      base.evm.reservedCodePrefix.isEmpty,
      "no earlier document on this network reserves a leading byte"
    )

  it should "hold the byte unsigned" in
    // 0xEF exceeds what a signed byte in this language holds, so a member typed
    // to a byte would carry -17 here. The case names the positive figure so a
    // narrowing cannot pass by agreeing with itself.
    assert(
      adopted.evm.reservedCodePrefix.contains(239),
      "0xEF is 239, and a signed narrowing would hold -17"
    )

  it should "add no operation to the table" in
    // The document is explicit that 0xEF stays an undefined instruction and
    // keeps aborting when executed. It reserves the byte for storage; it does
    // not define it.
    assert(
      adopted.evm.table eq base.evm.table,
      "a document that defines no operation reached the table"
    )

  it should "move no figure at all" in
    assert(
      adopted.evm.schedule eq base.evm.schedule,
      "a document that reprices nothing rebuilt the record"
    )

  it should "leave the bound on deployed code where it found it" in
    // The nearest member, and the one a component copied from the wrong
    // neighbor would move. Both constrain what a deployment may store.
    assert(
      adopted.evm.maxCodeSize == base.evm.maxCodeSize,
      "the length bound is EIP-170's and is not this document's to move"
    )

  it should "settle that member and nothing else in the machine" in
    assert(
      adopted.evm == base.evm.copy(reservedCodePrefix = Some(0xef)),
      "the adopting rules differ from the earlier ones by something other than the reserved byte"
    )

  it should "reach no facet outside the machine" in
    assert(
      (adopted.admission eq base.admission) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a rule about deployed code altered a facet the document does not name"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(3541)), "the journal must record what was adopted")
