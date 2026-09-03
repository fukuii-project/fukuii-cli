package org.fukuii.chainspec

import org.fukuii.bytes.UInt256

/** The fee market a fork runs, as the values deriving a base fee reads.
  *
  * ==Absent below the fork that introduces one, and that is not a sentinel==
  *
  * [[HeaderRules.feeMarket]] answers `None` for every rule set below the
  * proposal that adds a fee market, and `None` there means the header carries no
  * base fee at all rather than a market whose parameters happen to be zero. Four
  * clients model the same absence rather than defaulting it: `besu-eth/besu` @
  * `fdf1247c6d` selects between a `LegacyFeeMarket` and a `LondonFeeMarket`,
  * `NethermindEth/nethermind` @ `b92e2a4719` gates on `IsEip1559Enabled`,
  * `paradigmxyz/reth` @ `24f7cd94b0` carries `Option`-shaped base-fee params, and
  * `ethereumclassic/core-geth` @ `4185df450` reads
  * `config.IsEnabled(config.GetEIP1559Transition, ...)`.
  *
  * ==The three members are the three a derivation reads, and no more==
  *
  * A proposal's activation height is deliberately absent. Whether a block is the
  * first under a fee market is answered by resolving its parent's rules and its
  * own and comparing them -- [[UpgradeSchedule.at]] already does that on both
  * activation axes -- so a height here would encode one axis and duplicate a
  * fact the schedule owns. `NethermindEth/nethermind` carries an
  * `Eip1559TransitionBlock` and `besu-eth/besu` a `londonForkBlockNumber`
  * precisely because neither has a schedule that answers it.
  *
  * **Where the base fee GOES is not here either.** It is settled where a
  * transaction is settled rather than where a header is validated, and the two
  * network families this project serves answer it differently -- so the member
  * that expresses it arrives with a network that routes rather than burns, and
  * with the consultation that decision requires.
  *
  * @param initialBaseFee
  *   the charge the first block under this market states, there being no parent
  *   under the market to derive one from. `ethereum/execution-specs` @
  *   `20f7f6271` `forks/london/fork.py:76` and `ethereum/go-ethereum-pow` @
  *   `v1.10.26` `params/protocol_params.go` both state 1,000,000,000, and
  *   `besu-eth/besu` @ `fdf1247c6d` `LondonFeeMarket.java` carries it as a value
  *   a chain may override at genesis rather than as a constant -- which is why
  *   it is a member here rather than a literal in the derivation.
  * @param elasticityMultiplier
  *   what a block's limit is divided by to reach the target its gas use is
  *   measured against. **It is also what scales the parent's limit at the one
  *   block where a market begins**, which is the whole of the transition rule.
  * @param maxChangeDenominator
  *   the bound on how far one block may move the charge, as a fraction of the
  *   parent's.
  */
final case class FeeMarket(
    initialBaseFee: UInt256,
    elasticityMultiplier: BigInt,
    maxChangeDenominator: BigInt
)

/** What a fork decides about a header's own fields, rather than about what runs
  * inside the block.
  *
  * ==Forecast by [[UpgradeRules]] and landing with one member==
  *
  * That type states the test a facet's members are admitted by -- a facet holds
  * what the layer reading it needs, never before, so **a facet is smaller than
  * the concern it is named for and grows as the layers that read it land**. This
  * one is named for everything a fork settles about a header and carries the one
  * thing the layer built beside it reads.
  *
  * Its own note names what brought it: *"a base fee and a withdrawals root are
  * not rules the consensus mechanism sets ... what it wants is a header facet,
  * which nothing here reads yet."* A fee market is the first of those to exist,
  * and `org.fukuii.consensus.HeaderValidator` is the reader that makes the facet
  * admissible.
  *
  * ==Three header rules this build owes are deliberately NOT members==
  *
  * The cap on extra data, the bound on how far a block may move the gas limit,
  * and the floor under it are all held by the layer that checks them rather than
  * resolved per fork. [[UpgradeRules]] refuses the first outright and records
  * why: no client of four varies it by fork, and the one that puts it on a
  * fork-resolved interface still assigns one value to every release. **The other
  * two clear no higher bar** -- one client of five resolves them per fork and
  * none varies them -- so they are values the checker states, and the facet's
  * own admission test is what keeps them out.
  *
  * That test is worth restating because it runs against the instinct: a rule
  * being fork-INVARIANT is the argument for keeping it off a fork-resolved
  * record, not for putting it there where it can be read once.
  *
  * ==What a later fork adds, and why the shape admits it==
  *
  * A withdrawals root, a blob-gas schedule and a beacon root each arrive as a
  * further trailing header element with its own proposal.
  * [[org.fukuii.types.BlockHeader]] already encodes all of them, so what each
  * needs here is a member saying whether this fork requires it -- exactly the
  * shape [[feeMarket]] takes. None is built, because no layer reads one.
  *
  * @param feeMarket
  *   the fee market this fork runs, absent where it runs none. A header under a
  *   fork with one MUST carry a base fee and a header under a fork without one
  *   MUST NOT, which is a rule about the header's shape rather than about any
  *   value in it -- and it is the pair, not the presence alone, that
  *   `org.fukuii.consensus.HeaderValidator` checks.
  */
final case class HeaderRules(feeMarket: Option[FeeMarket])

object HeaderRules:

  /** A fork settling nothing about its headers, which is every fork below the
    * first fee market.
    */
  val Unset: HeaderRules = HeaderRules(feeMarket = None)
