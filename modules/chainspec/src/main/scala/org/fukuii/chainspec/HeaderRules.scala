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

/** What a fork settles about the blob gas its headers account for.
  *
  * ==A record and not a flag, because every source parameterizes it and three
  * forks past the first have already moved the figures==
  *
  * `ethereum/go-ethereum` @ `02872e9ef` (2026-09-11) carries a per-fork
  * `BlobConfig{Target, Max, UpdateFraction}` (`params/config.go:675-679`) and
  * fills it differently at four of them -- `params/config.go:337-360` gives
  * Cancun 3/6/3338477, Prague 6/9/5007716, BPO1 10/15/8346193 and BPO2
  * 14/21/11684671. `besu-eth/besu` @ `b330564a9` (2026-09-11) carries the same
  * three under the same names (`config/.../BlobSchedule.java:22-40`) with the
  * same Cancun and Prague figures. `ethereum/execution-specs` @ `0cc100eb1`
  * (2026-09-11) states them as per-fork constants instead --
  * `src/ethereum/forks/cancun/vm/gas.py:83-86` against
  * `forks/prague/vm/gas.py:93-96` and `forks/bpo1/vm/gas.py:94-100` -- and
  * moves all three across the same forks. **A published state fixture states the
  * record itself**: a Cancun case's own `config` carries
  * `blobSchedule.Cancun = {target 0x03, max 0x06, baseFeeUpdateFraction
  * 0x32f0ed}`.
  *
  * ==Blobs and not gas, which is the unit three of the four sources state==
  *
  * go-ethereum, besu and the fixture's own `config` all state a count of blobs
  * and multiply by a per-blob figure where a derivation needs gas. The
  * executable specification states gas directly at Cancun and Prague and then
  * switches to blobs itself from Osaka
  * (`forks/osaka/vm/gas.py:95-96`, `PER_BLOB * BLOB_SCHEDULE_TARGET`). Holding
  * blobs keeps the stored figure the one a configuration file states and leaves
  * the gas a derivation -- `org.fukuii.consensus.HeaderValidator` holds the
  * per-blob figure, because no fork varies it.
  *
  * ==Two of the published record's three members are absent, for two different
  * reasons==
  *
  * `baseFeeUpdateFraction` is read by no header rule at any fork this build has
  * surveyed: it prices blob gas, and the layers that need a price are the
  * machine and the settlement of a blob-carrying transaction. It sits on
  * `org.fukuii.evm.EvmRules` where the operation that reads it is, so that one
  * number is held once rather than in two facets.
  *
  * `max` is a header rule, and its reader is not built. Both production clients
  * check a header's own `blobGasUsed` against it in the SAME rule that checks
  * the excess -- `ethereum/go-ethereum` @ `02872e9ef`
  * `consensus/misc/eip4844/eip4844.go:111-116` and `besu-eth/besu` @
  * `b330564a9`
  * `ethereum/core/.../headervalidationrules/BlobGasValidationRule.java:67-78`,
  * each also requiring the figure to be a whole number of blobs. Neither check
  * needs a body, so neither is the block-level comparison against what the
  * transactions actually carried. **The member arrives with them**, on
  * [[HeaderRules]]'s own admission test.
  *
  * ==So it holds one member today, and that is still not a flag==
  *
  * The obvious objection is that a one-member record is an `Option[BigInt]` with
  * ceremony. Two things answer it. The published object is a record of three,
  * two of which have identified readers and named triggers above, so this grows
  * rather than being a shape chosen against nothing. And a bare number on
  * [[HeaderRules]] would read as a quantity of something unstated at every use
  * site, where every source in the field gives this figure a name.
  *
  * @param targetBlobs
  *   how many blobs a block is expected to carry. The parent's own excess plus
  *   what the parent spent is measured against it: the difference is this
  *   block's excess, and the figure is floored at zero rather than going
  *   negative.
  */
final case class BlobSchedule(targetBlobs: BigInt)

/** Which header fields a fork holds at a constant, rather than leaving them for
  * a block's producer to choose and its consensus mechanism to check.
  *
  * ==Why a selector and not the constants themselves==
  *
  * [[HeaderRules]]'s own admission test refuses a fork-invariant value a place
  * on a fork-resolved record, and the constants are fork-invariant: EIP-3675
  * fixes all three, so every network adopting it fixes the same three to the
  * same values. What varies per fork is only whether the rule is in force, and
  * that is the whole of what this carries.
  *
  * ==One member for three checks, where two clients write three==
  *
  * `ethereum/execution-specs` @ `20f7f6271a` (2026-08-26) checks each
  * separately inside one function -- `src/ethereum/forks/paris/fork.py:324`,
  * `:326` and `:328` -- and `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) wires
  * three named rules, `NoDifficultyRule`, `NoNonceRule` and
  * `ConstantOmmersHashRule`, into one builder in
  * `MainnetBlockHeaderValidator.mergeBlockHeaderValidator`. **Neither gates them
  * independently.** All three arrive in one document at one activation, so a
  * rule set that could hold two of the three would express a fork no
  * specification states.
  *
  * `org.fukuii.consensus.HeaderValidator` still reports which of the three
  * refused a header, because that is a diagnosis rather than a rule: the reason
  * a peer's block was rejected is what a divergence is read from.
  */
enum HeaderConstants:

  /** None of them: the producer chooses its difficulty, its seal and its
    * ommers, and whichever mechanism the network runs is what checks them.
    *
    * The answer every fork gives below the first that fixes any of them, which
    * is every fork this project has built until now.
    */
  case Unconstrained

  /** The difficulty, the seal's nonce and the ommers commitment are each fixed
    * to the value EIP-3675 states.
    *
    * Named for the document rather than for a mechanism or a network's word for
    * the upgrade that carried it, which is
    * [[DifficultyAdjustment.Eip100]]'s precedent on the facet beside this one
    * and is what `.claude/rules/nomenclature.md` requires of a name read by more
    * than one network family.
    */
  case Eip3675

/** What a fork decides about a header's own fields, rather than about what runs
  * inside the block.
  *
  * ==Forecast by [[UpgradeRules]], and each member arrived with its reader==
  *
  * That type states the test a facet's members are admitted by -- a facet holds
  * what the layer reading it needs, never before, so **a facet is smaller than
  * the concern it is named for and grows as the layers that read it land**. This
  * one is named for everything a fork settles about a header and carries what
  * the layer built beside it reads. **No count is stated, deliberately**: the
  * count is what goes stale on the commit that adds the next member, and the
  * record itself is where a reader finds it.
  *
  * Its own note names what brought the first: *"a base fee and a withdrawals
  * root are not rules the consensus mechanism sets ... what it wants is a header
  * facet, which nothing here reads yet."* A fee market is the first of those to
  * exist, and `org.fukuii.consensus.HeaderValidator` is the reader that makes
  * the facet admissible.
  *
  * [[constants]] arrived the same way and not by forecast: EIP-3675 fixes three
  * header fields to constants, the same validator is what compares them, and the
  * member exists because that comparison needs to know whether the rule is in
  * force. **What it is emphatically not is a second way to ask which consensus
  * mechanism a network runs** -- it answers one question about one header, and a
  * reader wanting the mechanism wants `org.fukuii.consensus.ConsensusEngine`.
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
  * ==What a later fork adds, and what the forecast got wrong about the first of
  * them==
  *
  * A blob-gas schedule and a beacon root each arrive as a further trailing
  * header element with its own proposal, and
  * [[org.fukuii.types.BlockHeader]] already encodes both. This section forecast
  * that each would need *"a member saying whether this fork requires it"*, and
  * for the blob-gas pair that is half the answer and the wrong half to state
  * alone: presence IS resolved per fork, and so are figures the field has
  * already moved three times past the fork that introduced them.
  * [[blobSchedule]] is therefore an option over a record rather than a flag --
  * the option answers presence, as a flag would, and the record answers what a
  * header at this fork must derive. [[BlobSchedule]] carries the evidence.
  *
  * **The beacon root is still forecast and still unbuilt**, and it is the case
  * the original sentence describes correctly: the proposal parameterizes
  * nothing, so a flag is the shape it wants, exactly as
  * [[carriesWithdrawalsRoot]] took.
  *
  * @param feeMarket
  *   the fee market this fork runs, absent where it runs none. A header under a
  *   fork with one MUST carry a base fee and a header under a fork without one
  *   MUST NOT, which is a rule about the header's shape rather than about any
  *   value in it -- and it is the pair, not the presence alone, that
  *   `org.fukuii.consensus.HeaderValidator` checks.
  * @param constants
  *   which of a header's fields this fork holds at a constant. [[HeaderConstants]]
  *   carries the evidence for the pair and for why one member answers for three
  *   fields.
  *
  *   **It admits a member here on the same test [[feeMarket]] met, and that test
  *   is what the second member is evidence for rather than against.** A rule
  *   being fork-INVARIANT keeps it off this record; this one is fork-resolved in
  *   the two clients read for it and in the executable specification, each of
  *   which enforces the constants at one fork and not at the fork below it.
  * @param carriesWithdrawalsRoot
  *   whether a header at this fork states a commitment over the block's
  *   withdrawals. EIP-4895 is what introduces the field, and
  *   [[org.fukuii.chainspec.proposals.eip.Eip4895]] carries the evidence.
  *
  *   **A flag rather than a record, because the proposal parameterizes
  *   nothing.** [[feeMarket]] carries three figures a derivation reads; this
  *   carries none, and there is nothing a network could set differently. What
  *   the root must BE is a function of the block's body and is produced where
  *   the body is -- `org.fukuii.execution.BlockOutput.withdrawalsRoot` -- so
  *   this member answers presence and never value.
  *
  *   **Both directions are rules**, as with [[feeMarket]]: a header below the
  *   proposal carrying a root is invalid exactly as one at or above it carrying
  *   none is. `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) resolves the identical
  *   pair per fork, selecting between a `WithdrawalsValidator.ProhibitedWithdrawals`
  *   whose check is *"withdrawalsRoot must be null when Withdrawals are
  *   prohibited"* and an `AllowedWithdrawals` whose check is the converse.
  * @param blobSchedule
  *   what a header at this fork must account for in blob gas, absent where the
  *   fork accounts for none. [[BlobSchedule]] carries the evidence for the
  *   record and for the two published members that are not in it.
  *
  *   **Both directions are rules, as with [[feeMarket]] and
  *   [[carriesWithdrawalsRoot]]**, and here the pair of header fields arrives as
  *   one link -- `org.fukuii.types.BlobGasTail` holds `blobGasUsed` and
  *   `excessBlobGas` together because no header carries one without the other --
  *   so presence is a single question rather than two.
  */
final case class HeaderRules(
    feeMarket: Option[FeeMarket],
    constants: HeaderConstants,
    carriesWithdrawalsRoot: Boolean,
    blobSchedule: Option[BlobSchedule]
)

object HeaderRules:

  /** A fork settling nothing about its headers, which is every fork below the
    * first fee market.
    */
  val Unset: HeaderRules =
    HeaderRules(
      feeMarket = None,
      constants = HeaderConstants.Unconstrained,
      carriesWithdrawalsRoot = false,
      blobSchedule = None
    )
