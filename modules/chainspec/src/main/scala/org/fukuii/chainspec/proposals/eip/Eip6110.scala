package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}

/** EIP-6110 -- deposits supplied by the execution layer, read out of the block's
  * own receipts.
  *
  * ==The one request source that is not a system call==
  *
  * Nothing is invoked for it. The records are parsed from logs ordinary
  * transactions already emitted, which is why a rule expressed as "check every
  * new system call" cannot see this source at all.
  * `org.fukuii.execution.ExecutionRequests.depositsIn` is the parse.
  *
  * ==A layout deviation refuses the BLOCK==
  *
  * The contract emits a fixed shape, so the specification refuses any deviation
  * rather than skipping the record -- a deviation means the contract is
  * misbehaving, and a lenient parser would accept a block the network rejects,
  * precisely when something is wrong.
  *
  * ==Its address is the one per-NETWORK value in the seam==
  *
  * Every other address these documents name is fixed by the proposal and
  * identical everywhere; this one is a deployment.
  * `org.fukuii.chainspec.networks.ethereum.Mainnet.requestRules` states this
  * network's and carries the sourcing, and the proposal's own constants table
  * marks that row **Mainnet** while leaving the Comment column empty for the
  * event signature beside it. The signature is
  * `org.fukuii.execution.ExecutionRequests.DepositEventSignature`, derived from
  * the event's own ABI rather than carried as a literal.
  */
object Eip6110:

  /** Adopting the document, which over a rule set changes nothing.
    *
    * ==An empty delta is a record, not a no-op==
    *
    * What this document adds is a source for [[Eip7685]]'s container, and that
    * container's own header flag is what a block consults; the address it needs
    * is the network's rather than the fork's. So there is no rule left for a
    * fork to switch. **Adopting it is still worth recording**: [[Component]]
    * states that the component list says which proposals were adopted while the
    * rules say what adopting them did, so a Prague rule set that omitted this
    * entry would misreport what Prague is.
    *
    * **The trigger that would give it a real delta** is a fork whose request
    * SOURCES differ from Prague's. `besu-eth/besu` @ `b330564a9` already models
    * that as fork-resolved -- `MainnetRequestsProcessor.amsterdamRequestsProcessors`
    * is Prague's set plus two more -- so the set is per-fork in the field and is
    * hardcoded here. At the second such fork it becomes a member rather than a
    * constant.
    */
  val component: Component = Component(ProposalId.Eip(6110), identity)
