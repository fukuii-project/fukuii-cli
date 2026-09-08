package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, HeaderRules, ProposalId}

/** EIP-4895 -- the consensus layer pushes validator withdrawals into the
  * execution layer as operations rather than as transactions.
  *
  * ==The document is four changes and only one of them is a rule a fork
  * resolves==
  *
  * *"Beginning with the execution timestamp `FORK_TIMESTAMP`, execution clients
  * **MUST** introduce the following extensions to payload validation and
  * processing"* (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26),
  * `EIPS/eip-4895.md`, Final), over four sections: a new payload-level object,
  * a new field in the payload, a new field in the payload header, and a state
  * transition.
  *
  * **Three of the four are shapes rather than resolved values.**
  * `org.fukuii.types.Withdrawal` is the object and its
  * `[index, validator_index, address, amount]` encoding;
  * `org.fukuii.types.BlockBody` carries the list as its third element;
  * `org.fukuii.types.BlockHeader` carries the root as a trailing element. None
  * of the three is expressible as a delta over a rule set, because a codec does
  * not vary by fork -- what varies is whether a header at a height is REQUIRED
  * to carry the field, and that is the one member below.
  *
  * **The fourth is the state transition, and it is not a delta either.** The
  * credit is unconditional and parameterless: *"For each `withdrawal` in the
  * list ... the implementation increases the balance of the `address` specified
  * by the `amount` given"*, and *"This operation has no associated gas costs"*.
  * There is no figure a network could set differently, so
  * `org.fukuii.execution.Withdrawals` states it once and no facet resolves it.
  * What reaches this build's rule set is therefore one boolean, and the
  * asymmetry is worth naming: **the proposal's weight is almost entirely in
  * layers a fork does not parameterize.**
  *
  * ==Why the header member and not an execution one==
  *
  * A block below this proposal carries no withdrawals field and its header
  * states no root; a block at or above it carries a list, possibly empty, and
  * its header states the root over it. That pair is checked on a header alone,
  * by `org.fukuii.consensus.HeaderValidator`, which is what makes
  * [[org.fukuii.chainspec.HeaderRules]] the facet that reads it -- the same
  * route [[Eip1559]] took for its own presence-and-absence rule.
  *
  * **A second copy on the execution facet was declined.** The block layer needs
  * no fork rule to credit a list it was handed: `besu-eth/besu` @ `fdf1247c6d`
  * (2026-08-26) gates its own processing on `maybeWithdrawalsProcessor.isPresent()
  * && maybeWithdrawals.isPresent()`, and the second conjunct is the body's own
  * shape. A block whose body carries withdrawals its height does not admit
  * produces a commitment the header cannot match, so the fact is enforced
  * without being stored twice.
  *
  * ==What this build's rule set cannot express, and where it went instead==
  *
  * The document's activation is a timestamp -- `FORK_TIMESTAMP` 1681338455 --
  * and `org.fukuii.chainspec.UpgradeSchedule` already carries that axis, so it
  * is a schedule entry rather than anything here. The engine API that delivers a
  * payload's withdrawals is outside the execution layer entirely, as EIP-3675's
  * network-interface half already is.
  *
  * ==Ethereum Classic does not take this, which is why the upgrade is not a
  * bundle==
  *
  * `ethereumclassic/core-geth` @ `4185df450` `params/config_classic.go` sets
  * the other three proposals of the same Ethereum upgrade to one height at
  * `:101-103` and, at `:104`, carries this one commented out against an
  * explicit `nil`: `// EIP4895FBlock: nil, // Beacon chain push withdrawals as
  * operations`. **The omission is recorded rather than merely absent**, which
  * is a stronger reading than a missing entry -- a client that had not reached
  * the proposal would have no line for it. A network with no beacon chain has
  * nothing to push withdrawals from, so this is a divergence in the networks
  * rather than a lag:
  * [[Eip3651]], [[Eip3855]] and [[Eip3860]] compose over a proof-of-work rule
  * set on their own, and a component holding all four would make that
  * impossible to express.
  */
object Eip4895:

  /** A header at this fork states a commitment over the block's withdrawals.
    *
    * The value it must state is not here and could not be: it is a function of
    * the body, and `org.fukuii.execution.BlockOutput.withdrawalsRoot` is what
    * produces it. This settles only that the field is required, which is what a
    * node holding a header and no body can still check.
    */
  val withdrawalsCommitment: HeaderRules => HeaderRules = _.copy(carriesWithdrawalsRoot = true)

  /** Adopting the document, which over a rule set is adopting its one delta.
    *
    * Built from the general constructor rather than the machine-scoped one: the
    * delta does not reach the machine, and this proposal adds no operation, moves
    * no price and changes no charge. **A withdrawal is not observable from
    * inside the machine at all** -- it leaves a balance behind and nothing else,
    * which is what the document's *"firewalls off generic EVM execution from
    * this type of processing"* means in practice.
    */
  val component: Component =
    Component(ProposalId.Eip(4895), rules => rules.copy(header = withdrawalsCommitment(rules.header)))
