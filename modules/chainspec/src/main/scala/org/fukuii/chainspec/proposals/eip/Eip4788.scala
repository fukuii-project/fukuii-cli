package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, HeaderRules, ProposalId}

/** EIP-4788 -- each execution block commits to the root of the beacon block its
  * parent was built against, and a contract records a window of those roots.
  *
  * ==The document is three changes and only one of them is a rule a fork
  * resolves==
  *
  * A new trailing header field; a contract deployed at a named address; and a
  * call the block makes on its own account before any transaction runs
  * (`ethereum/EIPs` @ `d2a64c2d4` (2026-09-09), `EIPS/eip-4788.md`, Final).
  *
  * **The field is a shape, and `org.fukuii.types.BeaconRootTail` already
  * carries it.** A codec does not vary by fork; what varies is whether a header
  * at a height is REQUIRED to carry the element, and that is the one member
  * below.
  *
  * **The contract is not this client's to write.** It is deployed by an
  * ordinary transaction whose sender and address the document derives -- *"The
  * beacon roots contract is deployed like any other smart contract"* -- so it
  * reaches a network through that network's history or its genesis, never
  * through a rule set. [[Eip4895]] records the same asymmetry for its own
  * document, and the reading is the same here: **the proposal's weight is
  * almost entirely in layers a fork does not parameterize.**
  *
  * **The call is parameterless too.** Its caller, its target, its gas allowance
  * and its history length are stated once in the document's constants table and
  * moved by no fork and no network in this project's reference corpus, so they
  * sit on `org.fukuii.execution.SystemCall` beside the code that reads them
  * rather than on a facet a network could set differently. That is
  * [[org.fukuii.chainspec.HeaderRules]]'s own admission test applied: a rule
  * being fork-INVARIANT is the argument for keeping it OFF a fork-resolved
  * record.
  *
  * ==Why the header member and not an execution one==
  *
  * A block below this proposal carries no beacon-root element; a block at or
  * above it carries one. That pair is checked on a header alone, which is what
  * makes [[org.fukuii.chainspec.HeaderRules]] the facet that reads it -- the
  * route [[Eip1559]] and [[Eip4895]] both took for their own presence rules.
  *
  * **A second copy on the execution facet was declined**, on [[Eip4895]]'s
  * reasoning and with the same shape of evidence. The block layer needs no fork
  * rule to run a call it was handed: `besu-eth/besu` @ `b330564a9` (2026-09-09)
  * gates its `CancunPreExecutionProcessor` on
  * `currentBlockHeader.getParentBeaconBlockRoot().ifPresent(...)`, and
  * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-09)
  * `core/state_processor.go:165-167` gates on `if beaconRoot != nil` -- the
  * header's own shape in both cases. `NethermindEth/nethermind` @ `3a98e0818`
  * (2026-09-09) is the one that consults a fork flag as well,
  * `spec.IsBeaconBlockRootAvailable && !header.IsGenesis &&
  * header.ParentBeaconBlockRoot is not null`, and the conjunction is what the
  * member below plus the caller's own test reproduce.
  *
  * ==This one is unusual in what it commits to and cannot check==
  *
  * Every other commitment a header states is derivable from the block: a
  * transactions root, a receipts root, a withdrawals root. **This one is not.**
  * The value comes from a consensus layer over the engine API and there is
  * nothing in an execution client to check it against, so the presence rule
  * below is the whole of what this layer can enforce about the field.
  * `.claude/agents/forge.md`'s litmus still puts it here -- the system call it
  * drives alters the state root -- but the ROOT ITSELF is a value this client
  * receives rather than one it computes.
  *
  * ==What this build's rule set cannot express, and where it went instead==
  *
  * The document's activation is a timestamp -- `FORK_TIMESTAMP` 1710338135 --
  * and `org.fukuii.chainspec.UpgradeSchedule` already carries that axis, so it
  * is a schedule entry rather than anything here. The engine-API half that
  * delivers the root to this client is outside the execution layer entirely, as
  * [[Eip4895]]'s own is.
  *
  * ==Ethereum Classic does not take this, for the reason it does not take
  * EIP-4895==
  *
  * A network with no beacon chain has no beacon block root to commit to, and
  * `ethereumclassic/core-geth` @ `4185df450` carries no activation for it in
  * `params/config_classic.go`. **That is a divergence in the networks rather
  * than a lag**, and it is why this is a component of its own rather than part
  * of a bundle: a proof-of-work rule set has to be able to adopt the rest of
  * the same upgrade without this.
  */
object Eip4788:

  /** A header at this fork states the beacon block root its parent was built
    * against.
    *
    * The value it must state is not here and could not be derived anywhere in
    * this client: it originates on the consensus layer. This settles only that
    * the element is required, which is what a node holding a header alone can
    * still check -- and, in the other direction, that a header below the
    * proposal carrying one is invalid.
    */
  val beaconRootCommitment: HeaderRules => HeaderRules = _.copy(carriesParentBeaconBlockRoot = true)

  /** Adopting the document, which over a rule set is adopting its one delta.
    *
    * Built from the general constructor rather than the machine-scoped one: the
    * delta does not reach the machine, and this proposal adds no operation,
    * moves no price and changes no charge. **What it adds runs BESIDE the
    * machine rather than inside it** -- a system call is an ordinary invocation
    * of ordinary code, so every rule it runs under is one some other proposal
    * already set.
    */
  val component: Component =
    Component(ProposalId.Eip(4788), rules => rules.copy(header = beaconRootCommitment(rules.header)))
