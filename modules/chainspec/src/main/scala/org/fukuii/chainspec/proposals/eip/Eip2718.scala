package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}

/** EIP-2718 -- the envelope every later transaction format arrives in, adopted
  * here as a record and nothing else.
  *
  * ==A COMPONENT WITH NO DELTA, AND THAT IS THE FINDING RATHER THAN AN
  * OVERSIGHT==
  *
  * The document introduces no format of its own. Its own words:
  * *"`TransactionType` ... `TransactionPayload` is an opaque byte array whose
  * interpretation is dependent on the `TransactionType` and defined in future
  * EIPs"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2718.md`, Final). So there
  * is no operation to place, no price to move and no rule to settle -- what it
  * changes is how a transaction and a receipt are ENCODED, and this project
  * already encodes both that way at every height.
  *
  * `org.fukuii.types.Transaction` declares the tagged payloads and a codec
  * handling the tagged and untagged forms alike; `org.fukuii.types.Receipt`
  * carries the type on the receipt and prefixes the tag byte for every
  * non-legacy one. Neither branches on a height, and neither could: a network
  * that admits only the untagged format can never produce a tagged receipt, so
  * `org.fukuii.execution.AdmissionRules.admittedTypes` already decides which
  * branch is reachable. **There is no height at which one of these encodes
  * differently**, which is what a rule member would have to express and why none
  * is added.
  *
  * ==The two roots it also settles have no site to change==
  *
  * The document requires the transaction root and the receipt root to commit to
  * `TransactionType || TransactionPayload` for a tagged entry and to the plain
  * encoding for an untagged one. Nothing in this build computes either root, so
  * there is nothing to amend; the byte string such a computation would feed a
  * trie is what those two types already produce. **A future layer that computes
  * a root inherits the obligation** -- it is satisfied by construction only for
  * as long as that layer feeds it those bytes.
  *
  * ==So why record it at all==
  *
  * `org.fukuii.chainspec.UpgradeRules.components` is a journal of what a network
  * adopted, not a derivation of what its rules became. This network adopted this
  * document; that its rules were already in the shape the document requires is a
  * fact about this build and not about the network's history. Omitting the entry
  * would make the record disagree with the upgrade it describes, and the next
  * format's own document would then rest on an adoption nothing states.
  */
object Eip2718:

  /** Adopting the document, which changes no rule.
    *
    * The identity is written out rather than reached through
    * `org.fukuii.chainspec.Component.evm` with no proposals: this document does
    * not touch the machine, and a constructor that says it does would misfile
    * the one thing this entry states.
    */
  val component: Component = Component(ProposalId.Eip(2718), identity)
