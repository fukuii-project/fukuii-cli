package org.fukuii.chainspec

import org.fukuii.evm.{EvmRules, Proposal}
import org.fukuii.execution.{AdmissionRules, ExecutionRules}

/** Everything one upgrade settles, held as one value per facet.
  *
  * ==Why an enclosing type rather than the machine's rules alone==
  *
  * An upgrade is not confined to a layer. ECIP-1054 adopts ten Ethereum
  * proposals at once, and they land in four different places: six of them
  * change the machine, one changes how difficulty is targeted, one changes when
  * state is cleared, and one changes what a receipt contains. A schedule whose
  * entries resolved directly to [[org.fukuii.evm.EvmRules]] would be correct
  * until the first of those arrives and would then have to be reshaped at every
  * call site that had learned to read it.
  *
  * Three of the four now have a home. What settles when state is cleared and
  * what a receipt contains are both
  * [[org.fukuii.execution.ExecutionRules]]; what admits a transaction at all is
  * [[org.fukuii.execution.AdmissionRules]]; the machine's own rules are where
  * they always were.
  *
  * ==What a facet holds arrives with the layer that reads it==
  *
  * Never before -- a facet with no consumer is a shape chosen against no
  * evidence -- and the test governs a facet's MEMBERS as much as its existence.
  * So a facet here is smaller than the concern it is named for, and grows as
  * the layers that read it land.
  *
  * **A consensus facet was forecast here holding five things, and it landed
  * holding two.** The forecast read *difficulty targeting, header and ommer
  * validation, the seal rule, and where a block's reward goes*, on the reason
  * that a reward is set by which engine a network runs far more than by which
  * fork it is at. The field contradicts that reason outright:
  * `ethereum/execution-specs` @ `ccaaaba58` changes `BLOCK_REWARD` twice across
  * thirteen forks that all run one mechanism, and
  * `NethermindEth/nethermind` @ `c35ce1b1ab` varies `spec.BlockReward` at three
  * of its own fork definitions.
  *
  * Of the forecast's other four, each is a formula or a ruleset rather than a
  * value, so each belongs on the engine and each arrives with the layer that
  * validates what it governs. **One value inside one of them is a candidate to
  * land here and has not**: the maximum ommer count is fork-resolved in
  * nethermind, where EIP-3675 sets it to zero at a named fork and an
  * authority-round network's engine overwrites it -- and it is a bare constant
  * in besu, in `ethereum/go-ethereum-pow` @ `v1.10.26` and in the executable
  * specification, and an engine-trait method in `openethereum/openethereum` @
  * `v3.0.1`. **So one client of five resolves it the way this facet would**,
  * which is thin evidence, and nothing here reads it yet. It arrives with ommer
  * validation or not at all.
  *
  * **A receipts facet was forecast here and is deliberately not being built.**
  * The one fork-varying thing a receipt carries is settled where the
  * transaction is settled, and no surveyed client separates the two:
  * `ethereum/go-ethereum` @ `6bb0588ad` chooses the shape inline in
  * `core/state_processor.go`, and `besu-eth/besu` @ `c2addd9424` carries its
  * receipt factory flat on the same specification as its transaction
  * processor. So the rule sits on the execution facet and a fourth facet
  * holding one member no other layer reads is not drawn.
  *
  * ==What a rule set does NOT carry==
  *
  * No identity. A network's word for an upgrade is [[UpgradeId]] and it lives
  * on the schedule entry, not here, so that two networks running the same rules
  * on different schedules can share one of these rather than each holding a
  * copy that differs only in its label. That is the ordinary case for a test
  * network, which runs its mainnet's compositions at its own activations.
  *
  * ==The component list is a JOURNAL, and reading it as a set is wrong on a
  * live network==
  *
  * The natural reading of [[components]] is *the proposals in force here*, and
  * a production chain already falsifies it. `gnosischain/configs` @
  * `e542d13234` carries `eip1283Transition` at 1,604,400,
  * `eip1283DisableTransition` at 2,508,800 and `eip1283ReenableTransition` at
  * 7,298,030 in its mainnet genesis -- one proposal turned on, off, and on
  * again, on a chain that ran every one of those heights.
  * `NethermindEth/nethermind` @ `c35ce1b1ab` reads all three keys in
  * `ChainSpecStyle/ChainParameters.cs` and evaluates them in order rather than
  * as membership.
  *
  * [[adopting]] appends, so a network reaching that state records the same
  * proposal three times. **That is the correct behavior and is why the type
  * needs no change**: a set cannot represent the middle state at all, while an
  * ordered record of adoptions replays to the right answer. What such a record
  * cannot do is answer *is this proposal in force* by membership, and nothing
  * here offers that.
  *
  * ==Equality is a value comparison in every member, with one documented
  * residual==
  *
  * *"Do these two networks run the same rules"* is a question this project has
  * to answer, and it is answerable on this type: [[components]] is a vector of
  * an enum, every facet that is a record of values compares field by field,
  * and [[org.fukuii.evm.EvmRules]] was made comparable member by member for
  * exactly this reason. The residual is
  * [[org.fukuii.evm.PrecompileSet.equals]]'s -- a chain supplying its own
  * native as an anonymous class contributes reference equality -- and that
  * type documents why erring toward *different* is the safe direction.
  *
  * **A facet added here has to keep that.** A member typed as a function or as
  * an open interface would put the whole comparison back where the machine's
  * rules started, silently: two identical configurations built separately would
  * compare unequal, and the answer would then depend on how a caller happened
  * to construct its inputs rather than on the rules.
  *
  * @param components
  *   the proposals adopted to reach these rules, in the order they were
  *   adopted. **What a network's rules follow from is which proposals it
  *   adopted, never the label it put on them** -- two networks can share a
  *   label and run different rules. This is a record of what was applied and
  *   not a set of what is in force; see above.
  * @param evm
  *   the machine's rules: its operations, its prices, its precompiles and the
  *   behaviors a fork settles inside it.
  * @param execution
  *   what settling a transaction does around the machine.
  * @param admission
  *   what makes a transaction acceptable before any of that happens.
  * @param consensus
  *   what a block owes the mechanism that produced it. [[ConsensusRules]]
  *   carries the evidence for each member, and the count is deliberately not
  *   restated here -- a facet grows as the layers that read it land, and a
  *   number written beside it goes stale on the commit that grows it.
  *
  *   **It is the one facet an engine may overwrite after the schedule has
  *   resolved it**, which is the join `org.fukuii.consensus.ConsensusEngine`
  *   exists to be. The other three are the fork's answer and nothing rewrites
  *   them.
  *
  *   Three block-structure rules this build already owes are NOT here, and each
  *   is refused for a different reason rather than deferred as a group.
  *
  *   The cap on [[org.fukuii.types.BlockHeader.extraData]] is **fork-resolved
  *   in none of four trees read for it**, and it is not a consensus value
  *   either. `ethereum/execution-specs` @ `ccaaaba58` restates the same literal
  *   bound in all 24 of its fork modules and varies it in none;
  *   `ethereum/go-ethereum-pow` @ `v1.10.26` holds it as
  *   `params.MaximumExtraDataSize`, a package constant, and reads it inside the
  *   ethash engine; `besu-eth/besu` @ `c2addd9424` passes
  *   `BlockHeader.MAX_EXTRA_DATA_BYTES` into a header-validation rule at every
  *   mainnet definition. nethermind is the one that puts it on a fork-resolved
  *   interface and still does not vary it by fork -- it writes it once at its
  *   earliest fork and otherwise assigns one chain-spec parameter to every
  *   release spec. Where it does differ is by ENGINE, in that client's own
  *   data: its Clique networks carry a wider cap than its ethash and
  *   authority-round ones, which is the axis a facet resolved per upgrade
  *   cannot express.
  *
  *   Which header field-counts a network accepts **is** fork-resolved -- each
  *   trailing element arrives with the proposal that added it -- and it is
  *   still not this facet's, because a base fee and a withdrawals root are not
  *   rules the consensus mechanism sets. Filing a fork-structure rule under a
  *   mechanism is the membership error two production clients already shipped
  *   for EIP-2, and what it wants is a header facet, which nothing here reads
  *   yet.
  *
  *   The obligation to reject the seal case a network's engine does not write
  *   needs the engine's identity rather than a value a fork resolved, and
  *   `org.fukuii.consensus.ConsensusEngine` is what carries that.
  *
  *   **What would bring the first two here is a client resolving one of them
  *   per fork**, and none of the four does.
  */
final case class UpgradeRules(
    components: Vector[ProposalId],
    evm: EvmRules,
    execution: ExecutionRules,
    admission: AdmissionRules,
    consensus: ConsensusRules
):

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
  * operations to the machine can also settle what admits a transaction. A
  * component that only touches the machine is built through [[Component.evm]],
  * which cannot reach any other facet.
  *
  * **EIP-2 is the worked case for why this constructor is needed at all.** That
  * document moves a price and settles a behavior in the machine, and settles a
  * third rule outside it, so it is built from this constructor directly.
  *
  * A constructor scoped to admission alone now has one caller -- EIP-155, whose
  * whole delta is a single member of that facet -- and one is not evidence for
  * a combinator. What [[Component.evm]] earns is a guarantee, that a proposal
  * confined to the machine cannot reach past it; a document adopting one flag
  * has nothing to be kept away from, so a scoped constructor would buy the
  * caller brevity and nothing else.
  *
  * Reversing trigger: a second proposal whose delta is confined to admission.
  *
  * ==The delta is an arbitrary function, and that is a decision==
  *
  * Nothing constrains it to produce rules that have anything to do with [[id]],
  * so a component can pair one proposal's number with another's rules -- or
  * with no proposal's. [[UpgradeRules.adopting]] rebuilds the component list
  * from the ids it was passed, so the RECORD cannot be forged; the rules can.
  * **That is also why the record does not determine the rules**: it states
  * which proposals were adopted and in what order, and the rules state what
  * adopting them did.
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
