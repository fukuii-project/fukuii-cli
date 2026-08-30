package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Proposal, StorageMetering}

/** EIP-1716 -- the withdrawal of EIP-1283.
  *
  * ==A hardfork meta that removes rather than adds, which is why it is a
  * component at all==
  *
  * *"This meta-EIP specifies the changes included in the Ethereum hardfork that
  * removes [EIP-1283] from [Constantinople]"*, and its own list is headed
  * *"Removed EIPs"* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1716.md`, Final).
  * It changes nothing else.
  *
  * **The other hardfork metas in this build are not components**, because a
  * meta that only enumerates what a fork contains is expressed by the entry
  * adopting those proposals. This one has a delta of its own -- putting a rule
  * back -- so it needs a value to carry it, and `ProposalId.Eip(1716)` is the
  * document that specifies the change.
  *
  * ==The journal records an adoption AND a withdrawal, in order==
  *
  * `UpgradeRules.components` is an ordered record of what was applied rather
  * than a set of what is in force, which is what lets a network record
  * EIP-1283 adopted and then removed. A set could not represent it at all. On
  * this network both entries sit at one height, so the record reads as a
  * proposal specified for a fork and taken out of it before it ever ran --
  * which is what happened.
  *
  * ==It is a RESTORATION, not a new rule==
  *
  * The scheme it returns to is the one every earlier fork ran, so this delta is
  * `StorageMetering.Legacy` and not a third case. A reader checking whether
  * Petersburg introduced anything of its own will find that it did not.
  *
  * ==Order-dependence, which the document states and a schedule can get wrong==
  *
  * *"If `Petersburg` and `Constantinople` are applied at the same block,
  * `Petersburg` takes precedence: with the net effect of EIP-1283 being
  * disabled."* A schedule resolving the two entries in the other order would
  * leave the network running the net scheme for ever, and nothing about the
  * rules themselves would look wrong. `MainnetSpec` pins the order in both
  * directions for that reason.
  */
object Eip1716:

  /** Storage is priced from what the slot holds now, as it was before
    * EIP-1283.
    */
  val legacyMetering: Proposal = _.copy(storageMetering = StorageMetering.Legacy)

  /** Adopting the document, which is adopting its one removal. */
  val component: Component = Component.evm(ProposalId.Eip(1716), legacyMetering)
