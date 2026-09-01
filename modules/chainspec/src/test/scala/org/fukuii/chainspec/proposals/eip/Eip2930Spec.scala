package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.networks.ethereum
import org.fukuii.chainspec.{ProposalId, UpgradeRules}
import org.fukuii.types.TransactionType
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-2930 changes: a format a block may carry, and what
  * declaring an account or a slot ahead of running costs.
  *
  * ==Through [[Eip2930.component]], because the wiring is what is untested==
  *
  * Both deltas are reachable on their own, so a spec calling one directly passes
  * with the component wired to nothing. This document's wiring is worth more
  * than most: it is the first here whose component reaches two facets, so a
  * component built from the machine-scoped constructor would apply the prices
  * and admit nothing.
  *
  * ==What this file does NOT assert==
  *
  * That a transaction of the admitted format is actually charged for its
  * declaration. That is `org.fukuii.execution.IntrinsicGasSpec`'s, over a
  * schedule holding a distinct value in every field -- a charge asserted here
  * against this network's real figures could not tell the two access-list prices
  * apart from anything else.
  */
class Eip2930Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.muirGlacier

  private val adopted: UpgradeRules = base.adopting(Eip2930.component)

  "adopting EIP-2930" should "let a block carry the declaring format" in
    assert(
      adopted.admission.admittedTypes.contains(TransactionType.AccessList),
      "the document introduces transaction type 1 and a fork adopting it must carry one"
    )

  it should "not have carried it before it was adopted" in
    assert(
      !base.admission.admittedTypes.contains(TransactionType.AccessList),
      "a format admitted before the document that defines it makes this delta unobservable"
    )

  it should "go on carrying the format that predates the envelope" in
    // ADDED, not replaced. A delta assigning the set rather than growing it
    // would refuse every transaction this network has ever carried, and would
    // satisfy the two cases above while doing so.
    assert(
      adopted.admission.admittedTypes.contains(TransactionType.Legacy),
      "the untagged format stays valid, and the document adds a format beside it"
    )

  it should "carry exactly those two and no more" in
    // The formats above type 1 arrive with their own documents, at later
    // upgrades. A delta admitting the whole enumeration would pass every case
    // above.
    assert(
      adopted.admission.admittedTypes == Set(TransactionType.Legacy, TransactionType.AccessList),
      "a format no document at this height defines was admitted"
    )

  it should "price a declared account at two thousand four hundred" in
    assert(
      adopted.evm.schedule.transactionAccessListAddress == BigInt(2400),
      "ACCESS_LIST_ADDRESS_COST is 2400"
    )

  it should "price a declared storage key at one thousand nine hundred" in
    assert(
      adopted.evm.schedule.transactionAccessListStorageKey == BigInt(1900),
      "ACCESS_LIST_STORAGE_KEY_COST is 1900"
    )

  it should "have priced neither before it was adopted" in
    // Both, rather than either: a delta that wrote one figure into both members
    // would satisfy a check on one of them.
    assert(
      base.evm.schedule.transactionAccessListAddress == BigInt(0) &&
        base.evm.schedule.transactionAccessListStorageKey == BigInt(0),
      "a network below this document declares nothing, and zero is how that is stated"
    )

  it should "price the two differently" in
    // The cheapest way to get this wrong is one constant read at both sites,
    // and the two figures are close enough that a reader does not notice.
    assert(
      adopted.evm.schedule.transactionAccessListAddress != adopted.evm.schedule.transactionAccessListStorageKey,
      "an account and a slot are declared at different prices"
    )

  it should "leave every other price in the record where it found it" in
    assert(
      adopted.evm.schedule ==
        base.evm.schedule.copy(
          transactionAccessListAddress = BigInt(2400),
          transactionAccessListStorageKey = BigInt(1900)
        ),
      "the delta moved a price this document does not name"
    )

  it should "leave the signature rules exactly where it found them" in
    // The format states its chain as a field, so neither the rule about a
    // legacy `v` folding an identifier in nor the bound on `s` is this
    // document's -- and a delta reaching either would be refusing or admitting
    // transactions of the untagged format on this document's authority.
    assert(
      adopted.admission.signatureMayCarryChainId == base.admission.signatureMayCarryChainId &&
        adopted.admission.signatureSMustBeLow == base.admission.signatureSMustBeLow,
      "a document about a new format changed a rule about the old one"
    )

  it should "leave the operation table and the precompile set untouched" in
    assert(
      (adopted.evm.table eq base.evm.table) && (adopted.evm.precompiles eq base.evm.precompiles),
      "an intrinsic repricing reached the machine's operations or its natives"
    )

  it should "leave the machine's warm-and-cold rule alone" in
    // The document's benefit depends on that rule being in force, which is
    // EIP-2929's to settle. A delta setting it here would make this document
    // able to turn on a scheme it only feeds.
    assert(
      adopted.evm.stateAccessMetering == base.evm.stateAccessMetering,
      "the scheme this document declares into is not this document's to switch on"
    )

  it should "leave the consensus and settlement facets untouched" in
    assert(
      (adopted.consensus eq base.consensus) && (adopted.execution eq base.execution),
      "a document reaching admission and the machine wrote a third facet"
    )

  it should "record itself in the component list" in
    assert(adopted.components.contains(ProposalId.Eip(2930)), "the journal must record what was adopted")
