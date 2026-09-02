package org.fukuii.chainspec.proposals.ecip

import org.fukuii.chainspec.{Component, ConsensusRules, ProposalId}

/** ECIP-1099 -- from one height onward an epoch is twice as long.
  *
  * ==The document states the height as a named constant and gives one per
  * network==
  *
  * Its specification assigns `ETCHASH_FORK_BLOCK := 11_700_000` for Ethereum
  * Classic mainnet and annotates it `(Epoch 390)`, assigns `2_520_000` for
  * Mordor, and states of the third network that *"no upgrade is required"*
  * (`ethereumclassic/ECIPs` @ `0adb420` (2026-07-29), `_specs/ecip-1099.md`,
  * status **Final**, type Standards Track, **category Core**, titled
  * *"Calibrate Epoch Duration"*).
  * **So the figure below is one network's and is not a default** --
  * [[org.fukuii.chainspec.ConsensusRules.ecip1099Activation]] states that from
  * the other side, and a default there would be one network's answer wearing no
  * network's name.
  *
  * **Its category is what separates it from the entry below it on this
  * network's schedule.** This is a Core Standards Track proposal and its own
  * Specification says so: *"The oldEpochLength (30000) changes to
  * newEpochLength (60000) at a given `ETCHASH_FORK_BLOCK` (hardfork
  * required)"*. The proposal activating 320,000 blocks earlier is an
  * `ECBP` -- a recommendation a client may decline -- which is why one of the
  * two is a rule change here and the other reaches no rule at all.
  *
  * ==One delta, and the mechanism it drives is already built==
  *
  * This file supplies a height and nothing else. What reads it is
  * `org.fukuii.consensus.pow.Ethash.epochLengthAt`, which answers
  * `Ecip1099EpochLength` at or above the height and `EpochLength` below it, and
  * `org.fukuii.consensus.pow.EthashEngine`, which threads the same value
  * through the cache it validates a seal against.
  *
  * **The half of the proposal most easily lost is already handled and is not
  * this delta's to state**: the seed chain continues to be counted in legacy
  * epochs, because the document requires it -- *"To avoid re-use of seeds
  * oldEpochLength will continue to be used within the seedHash function"* --
  * and `Ethash.seedFor` carries that reading with its own sourcing. A delta
  * that moved a second constant here would be repairing something already
  * right.
  *
  * ==Two implementations state the same height, and each files it where it
  * files its other per-fork values==
  *
  * `ethereumclassic/core-geth` @ `4185df450` carries
  * `ECIP1099FBlock: big.NewInt(11_700_000)` in `params/config_classic.go:87`,
  * beside `DisposalBlock`, which is
  * [[org.fukuii.chainspec.ConsensusRules.difficultyBombRemovedFrom]] -- one
  * height gating one question about what is in force at a block, and the same
  * shape twice. `besu-eth/besu-etc` @ `eb4248c997` carries
  * `"thanosBlock": 11700000` in `config/src/main/resources/classic.json:13`,
  * and its `ClassicProtocolSpecs.thanosDefinition` installs
  * `EpochCalculator.Ecip1099EpochCalculator` on the fork-resolved
  * specification -- which is the reading that puts this value on a rule set
  * rather than on the engine.
  */
object Ecip1099:

  /** An epoch is [[org.fukuii.consensus.pow.Ethash.Ecip1099EpochLength]] blocks
    * long at block 11,700,000 or above.
    *
    * **The height is inclusive**, which is the document's own comparison rather
    * than this delta's reading of it: its `calcEpochLength` answers the old
    * length while `block < ETCHASH_FORK_BLOCK`, so the figure written here is
    * the first block sized by the new one.
    *
    * **Absent rather than zero on a network without the rule.** Zero is a
    * height like any other, and a network stating it would size every epoch
    * from its genesis block by the longer length.
    */
  val calibratedEpochDuration: ConsensusRules => ConsensusRules =
    _.copy(ecip1099Activation = Some(BigInt(11700000)))

  /** Adopting the document, which is adopting its one delta.
    *
    * Built from the general constructor rather than a scoped one:
    * [[org.fukuii.chainspec.Component.evm]] reaches the machine and this
    * document does not touch it.
    */
  val component: Component =
    Component(ProposalId.Ecip(1099), rules => rules.copy(consensus = calibratedEpochDuration(rules.consensus)))
