package org.fukuii.chainspec

import org.fukuii.evm.{EvmRules, Proposal}

/** Everything one upgrade settles, held as one value per facet.
  *
  * ==Why an enclosing type exists while only one facet does==
  *
  * An upgrade is not confined to a layer. ECIP-1054 adopts ten Ethereum
  * proposals at once, and they land in four different places: six of them
  * change the machine, one changes how difficulty is targeted, one changes when
  * state is cleared, and one changes what a receipt contains. A schedule whose
  * entries resolved directly to [[org.fukuii.evm.EvmRules]] would be correct
  * until the first of those arrives and would then have to be reshaped at every
  * call site that had learned to read it.
  *
  * Building the enclosing value now costs one indirection. Retrofitting it
  * costs every consumer. So the facet that exists is declared and the ones that
  * do not are named below rather than guessed at.
  *
  * ==The facets not here yet, and what brings each one==
  *
  * Named so that a deferred layer is a row rather than a gap. Each arrives with
  * the layer that reads it, never before -- a facet with no consumer is a shape
  * chosen against no evidence, and the three clients surveyed for this
  * boundary carry these outside their EVM module in every case.
  *
  *   - **consensus** -- difficulty targeting, header and ommer validation, the
  *     seal rule. Arrives with the consensus engine.
  *   - **execution** -- the transaction processor, the block processor, the
  *     block reward and who receives it. Arrives with block execution.
  *   - **receipts** -- what a receipt states and how it is encoded. Arrives
  *     with the layer that builds one.
  *   - **admission** -- what makes a transaction acceptable at all. Arrives
  *     with the layer that admits transactions.
  *
  * ==What a rule set does NOT carry==
  *
  * No identity. A network's word for an upgrade is [[UpgradeId]] and it lives
  * on the schedule entry, not here, so that two networks running the same rules
  * on different schedules can share one of these rather than each holding a
  * copy that differs only in its label. That is the ordinary case for a test
  * network, which runs its mainnet's compositions at its own activations.
  *
  * ==Equality is not a value comparison, and it never was==
  *
  * [[org.fukuii.evm.EvmRules]] carries a function member and two members that
  * are plain classes with no equality of their own, so comparing two of these
  * with `==` compares those three by reference. A caller wanting to know
  * whether two networks run the same rules has to say what *same* means; this
  * type does not answer it, and a test written as though it did would pass or
  * fail on whether a value was built once or twice.
  *
  * @param components
  *   every proposal adopted to reach these rules, in the order they were
  *   adopted. This is what determines the rules -- never the label the network
  *   put on them, which two networks can share while running different rules.
  * @param evm
  *   the machine's rules: its operations, its prices, its precompiles and the
  *   behaviors a fork settles.
  */
final case class UpgradeRules(components: Vector[ProposalId], evm: EvmRules):

  /** These rules with each component adopted, in the order given.
    *
    * Order is the caller's to state and is not always free: two components
    * touching one price compose to whichever ran last.
    *
    * ==The recorded list is the list adopted, whatever a delta did==
    *
    * [[components]] is rebuilt here from the components actually passed rather
    * than taken from whatever the deltas returned, so a delta cannot quietly
    * add to or drop from the record of what produced these rules. **A component
    * that alters a facet it does not name is the seam failing**, and the facets
    * it leaves alone survive as the same values rather than as equal copies --
    * which is what makes that testable rather than merely intended.
    */
  def adopting(added: Component*): UpgradeRules =
    val changed = added.foldLeft(this)((rules, component) => component.delta(rules))
    changed.copy(components = this.components ++ added.map(_.id))

/** One proposal, and what adopting it changes.
  *
  * The delta is written over the whole rule set rather than over one facet,
  * because a proposal is not confined to one: the same document that adds
  * operations to the machine can also change how a receipt is written. A
  * component that only touches the machine is built through [[Component.evm]],
  * which cannot reach any other facet.
  *
  * ==The delta is an arbitrary function, and that is a decision==
  *
  * Nothing constrains it to produce rules that have anything to do with [[id]],
  * so a component can pair one proposal's number with another's rules -- or
  * with no proposal's. [[UpgradeRules.adopting]] rebuilds the component list
  * from the ids it was passed, so the RECORD cannot be forged; the rules can.
  *
  * **It is left open because the field's shape is the same one.**
  * `ethereumclassic/core-geth` @ `4185df450` gates each proposal on a predicate
  * over the chain configuration and lets the enabled body do whatever it does;
  * nothing there checks that an EIP-150 branch changes EIP-150's prices either.
  * A delta narrow enough to be checkable would have to enumerate what a proposal
  * may touch, which is the thing no specification bounds -- ECIP-1054 alone
  * reaches four layers.
  *
  * **The asymmetry with [[org.fukuii.evm.GasForwarding]] is deliberate and
  * worth naming**, because the two sit beside each other and pull opposite ways.
  * That type admits only the forms the field uses, so an out-of-module author
  * cannot place a value in it that its own arithmetic rejects. This one takes an
  * arbitrary function from anyone. The difference is what the wrong value costs
  * to detect: a bad forwarding fraction is silent, arithmetic, and shows up as a
  * gas figure nobody can source, while a delta that does not match its id
  * produces rules a certification corpus runs against and disagrees with. One
  * needs the type to refuse it; the other is caught by running it.
  */
final case class Component(id: ProposalId, delta: UpgradeRules => UpgradeRules)

object Component:

  /** A component that changes the machine and nothing else.
    *
    * The proposals compose left to right, exactly as
    * [[org.fukuii.evm.EvmRules.applying]] composes them, and no other facet
    * is reachable from here -- which is the point of the constructor rather
    * than an incidental property of it.
    */
  def evm(id: ProposalId, proposals: Proposal*): Component =
    Component(id, rules => rules.copy(evm = rules.evm.applying(proposals*)))
