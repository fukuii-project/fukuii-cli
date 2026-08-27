package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Precompile, PrecompileSet, Proposal}

/** EIP-197 -- the optimal ate pairing check on `alt_bn128`, at `0x08`.
  *
  * ==One entry, and its price is two figures rather than one==
  *
  * *"Address: 0x8"*, and *"The gas costs of the precompiled contract are
  * `80 000 * k + 100 000`, where `k` is the number of points or, equivalently,
  * the length of the input divided by 192"* (`ethereum/EIPs` @ `dbfa6bee83`,
  * `EIPS/eip-197.md`, Final). Both are read out of the rules the delta is
  * applied to, so EIP-1108 -- which moves them to 34,000 and 45,000 -- is a
  * repricing in the schedule plus a rebuild of this entry.
  *
  * ==Read with EIP-196 and adopted apart from it==
  *
  * This document's abstract says the two are combined *"to verify zkSNARKs in
  * Ethereum smart contracts"*, and each names the other by number. Neither
  * declares the other a dependency, though, and nothing in this entry reaches
  * either of that one's: a pairing check answers at its own address over its
  * own encoding. So a network taking one without the other gets exactly what
  * that one document describes, and the two are separate components for that
  * reason rather than for this network's convenience.
  *
  * ==The rule this document adds that EIP-196 has no equivalent of==
  *
  * *"For `G_2`, in addition to that, the order of the element has to be checked
  * to be equal to the group order"*. That check is the curve's and lives with
  * it in `org.fukuii.crypto.AltBn128`; what is here is only which address runs
  * it and at what price.
  */
object Eip197:

  /** The native joins the set at the address the document names, priced from
    * the two figures the rules already state.
    */
  val pairingCheck: Proposal =
    rules =>
      rules.copy(precompiles =
        rules.precompiles.adding(
          PrecompileSet.AltBn128PairingCheck,
          Precompile.AltBn128PairingCheck(
            rules.schedule.precompileAltBn128PairingBase,
            rules.schedule.precompileAltBn128PairingPerPoint
          )
        )
      )

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(197), pairingCheck)
