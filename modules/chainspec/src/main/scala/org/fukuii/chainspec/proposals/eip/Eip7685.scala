package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, HeaderRules, ProposalId}

/** EIP-7685 -- a general account for what the execution layer asks of the
  * consensus layer.
  *
  * ==What this document is, and what it deliberately is not==
  *
  * It defines a **container and a commitment**, and no requests of its own. A
  * block gathers a list of typed records, the header commits to a hash over that
  * list, and which records may appear is left to the documents that introduce
  * them. So adopting this document alone gives a fork a header field and an
  * always-empty list; the records arrive with [[Eip6110]], [[Eip7002]] and
  * [[Eip7251]], each of which needs this one and none of which this one needs.
  *
  * **This component is the header half only.** Deriving the commitment requires
  * an executed body -- the deposit records are parsed from the block's own
  * receipts and the two others are return data from system calls -- so the fold
  * and its comparison live with the layer that has a body, and this document's
  * rule set contribution is a single flag.
  *
  * ==The commitment is a hash over the records PRESENT, which is what makes
  * absent different from empty==
  *
  * `ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/requests.py:289-308` hashes the entries the block
  * produced and nothing else, and the callers in `fork.py:789-810` append a
  * record only where its source yielded bytes. So a request type that produced
  * nothing contributes no entry, which is a different commitment from
  * contributing an empty one. `.claude/protocols/consensus-change.md` carries
  * this as a standing fact for the seam rather than for this document alone.
  *
  * ==Presence is checked in both directions, and the clients are three ways
  * apart on it==
  *
  * `org.fukuii.chainspec.HeaderRules.carriesRequestsHash` is the flag and
  * `org.fukuii.consensus.HeaderValidator` is its reader. That reader's own
  * documentation carries the survey -- the executable specification settles it
  * structurally rather than as a header rule, `NethermindEth/nethermind` checks
  * both directions, `besu-eth/besu` checks presence only, and
  * `ethereum/go-ethereum` checks neither -- and states the reversing trigger.
  * **It is not restated here**; one home for the survey is the point.
  */
object Eip7685:

  /** A header at these rules commits to the requests its block produced. */
  val requestsCommitment: HeaderRules => HeaderRules = _.copy(carriesRequestsHash = true)

  /** Adopting the document, which at the header layer is adopting its one flag. */
  val component: Component =
    Component(ProposalId.Eip(7685), rules => rules.copy(header = requestsCommitment(rules.header)))
