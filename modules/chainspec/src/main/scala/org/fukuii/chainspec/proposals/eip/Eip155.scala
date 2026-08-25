package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.execution.AdmissionRules

/** EIP-155 -- a legacy signature may name the chain it was made for.
  *
  * ==Adopting it is a flag, and that is the seam working rather than the
  * document being small==
  *
  * The proposal specifies a second signing preimage of nine elements, an
  * encoding that folds the identifier into `v` as
  * `{0,1} + CHAIN_ID * 2 + 35`, and a rule that a transaction naming another
  * chain is not this chain's (`ethereum/EIPs` @ `15f61ed0f`,
  * `EIPS/eip-155.md`, Final). None of that is here, and none of it is missing:
  * the preimage and the encoding are the transaction's own, in
  * [[org.fukuii.types.SigningPreimage]] and
  * [[org.fukuii.types.SignatureScheme]], and the comparison against this
  * network belongs to whoever holds the network's identifier, which is
  * [[org.fukuii.execution.TransactionAdmission]]. What a fork settles is
  * whether the later scheme is admitted at all, and that is one member of one
  * facet.
  *
  * **The rule permits the later scheme rather than requiring it**, and both
  * halves of that are [[org.fukuii.execution.AdmissionRules.signatureMayCarryChainId]]'s
  * to state; the specification writes them by replacement rather than as a
  * flag, so a reader checking this against it will not find a boolean there.
  *
  * ==The refusal of ANOTHER chain's transaction is not gated by this, and the
  * order between the two is observable==
  *
  * A signature can break both rules at once -- naming a chain at rules that
  * admit no identifier from anyone -- and below this proposal the refusal is
  * the signature's rather than the chain's.
  * [[org.fukuii.execution.TransactionAdmission.senderOf]] holds that ordering
  * and the sources for it; this document is only what flips the first of the
  * two.
  *
  * ==This document is cited twice for two different things, and only one of
  * them is here==
  *
  * Its § *Parameters* gives `CHAIN_ID: 1 (main net)` and its § *List of Chain
  * ID's* is the registry a network's identifier is read from -- which is why
  * [[org.fukuii.chainspec.networks.ethereum.Mainnet.network]] cites the same
  * document. That is an identity a network holds, not a rule a fork switches
  * on, so the two readings stay in the two places rather than one importing the
  * other.
  *
  * ==A client can gate this on the fork beside it, and one does==
  *
  * `ethereum/go-ethereum` @ `6bb0588ad` carries `EIP155Block` and `EIP158Block`
  * as separate fields and sets both to 2,675,000 on its mainnet.
  * `besu-eth/besu` @ `c2addd9424` has no `eip155Block` at all: its
  * `config/src/main/resources/mainnet.json` carries `"eip158Block": 2675000`,
  * and `MainnetProtocolSpecs.spuriousDragonDefinition` is where a `chainId`
  * first reaches its `TransactionValidatorFactory`. The two answers agree on
  * this network and would not on one that took the two proposals apart, which
  * is the case a component list expresses and a fork-named branch cannot.
  */
object Eip155:

  /** A `v` that names a chain becomes a signature this network will read.
    *
    * Written over the admission facet, because a transaction refused for its
    * signature never reaches the machine at all.
    */
  val chainIdInSignature: AdmissionRules => AdmissionRules = _.copy(signatureMayCarryChainId = true)

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one: `Component.evm`
    * reaches the machine and this document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Eip(155), rules => rules.copy(admission = chainIdInSignature(rules.admission)))
