package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{Component, ProposalId}

/** ECIP-1017 -- the emission steps down by a fifth every five million blocks,
  * and at the first step the ommer miner's reward changes rule rather than
  * scale.
  *
  * ==The document specifies a ladder and never an activation==
  *
  * Its specification section states the eras: *"Era 1 (blocks 1 -
  * 5,000,000)"*, *"Every Era will last for 5,000,000 blocks"* and *"All rewards
  * will be reduced at a constant rate of 20% upon entering a new Era"*. One
  * figure is not a reduction, and the document says so where it introduces the
  * second era -- *"Era 2 represents a reduction of 20% of Era 1 values, while
  * also reducing uncle rewards to uncle miners to be the same value as the
  * reward to the winning miner for including the uncle(s)"*
  * (`ethereumclassic/ECIPs` @ `e36ef7f10166769aa3ac469aaf27ba5b0cacb198`,
  * `_specs/ecip-1017.md`, status Final). **No block number appears in that
  * section at all**; the one mention of five million elsewhere in the document
  * is its rationale reasoning about supply.
  *
  * ==The ladder has no last era, so no rule set can hold its figures==
  *
  * A rule set resolved per fork maps a height onto values written out in
  * advance, and an unbounded ladder has no such map. So the figures are
  * computed rather than stored, and `org.fukuii.consensus.pow.EthashEngine`
  * computes them from an era length.
  * [[org.fukuii.chainspec.networks.ethereumclassic.Upgrades.frontier]] holds the
  * amount that ladder reduces, and adopting this document does not change it:
  * the first era pays exactly that amount, which is why it survives the
  * proposal that eventually steps it down.
  *
  * ==Adopting the document writes no rule here, and that is checkable rather
  * than asserted==
  *
  * Two of the three implementations read here gate the ladder on an activation
  * height and one does not. `ethereumclassic/core-geth` @ `4185df450`
  * dispatches in `params/mutations/rewards.go` on
  * `config.IsEnabled(config.GetEthashECIP1017Transition, header.Number)`, whose
  * accessor resolves to `ECIP1017FBlock` in
  * `params/types/coregeth/chain_config_configurator.go`, and reads the era
  * length inside the function so selected rather than from the fork.
  * `besu-eth/besu-etc` @ `eb4248c997` splits it the same way, its Gotham
  * definition in `ClassicProtocolSpecs` installing `ClassicBlockProcessor` on
  * the fork-resolved specification and handing it
  * `genesisConfigOptions.getEcip1017EraRounds()`, which is chain-wide.
  * `openethereum/openethereum` @ `v3.0.1` carries `ecip1017_era_rounds` on its
  * engine parameters with no activation field anywhere, and defaults it to
  * `u64::max_value()` in `ethcore/engines/ethash/src/lib.rs` -- an era length no
  * height reaches, which is the first era forever.
  *
  * **That gate cannot change an answer on any network either of the two
  * configures.** The first era pays the base amount, an ommer's miner the
  * age-scaled eighths, and the winner a thirty-second of the base per ommer,
  * which is exactly what the ungated branch of core-geth's same file computes
  * for a network that never adopted this. Gating therefore differs from not
  * gating only BELOW the activation, and the first era already covers that
  * region wherever the activation is no higher than the era length. Every chain
  * configuration in that tree setting both fields satisfies it:
  * `ClassicChainConfig` pairs 5,000,000 with 5,000,000 and `MessNetConfig`
  * pairs 5 with 5,000, both in `params/config_classic.go`, and
  * `params/config_mordor.go` pairs 0 with 2,000,000.
  *
  * **Reversal trigger, checkable rather than rhetorical: a network configuring
  * an activation GREATER than its era length.** Above the era length and below
  * the activation the two readings disagree outright -- an ungated engine has
  * already stepped the reward down while a gated one still pays the base -- so
  * the member becomes observable and is owed at that moment. A member carried
  * before then would be a rule no network in this project's scope can produce a
  * falsifying vector for.
  *
  * ==What the rule set records is the adoption itself==
  *
  * [[org.fukuii.chainspec.UpgradeRules.adopting]] records a component's id
  * independently of what its delta does, so a rule set that has adopted this
  * document is distinguishable from one that has not even though the two carry
  * the same values. That distinction is what the schedule needs: the height is a
  * fork point on the wire whether or not any rule value moves across it.
  */
object Ecip1017:

  /** Adopting the document, which settles no member of any facet.
    *
    * Built from the general constructor rather than [[Component.evm]], which
    * would claim this document reaches the machine. It reaches no facet at all:
    * the ladder is the engine's, per this object's own documentation, and the
    * amount it reduces is already the one the network launched with.
    */
  val component: Component = Component(ProposalId.Ecip(1017), identity)
