package org.fukuii.consensus

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.{FeeMarket, UpgradeRules}
import org.fukuii.types.BlockHeader

/** Why a header is not valid against its parent.
  *
  * ==One reason per rule, because the reason is what a divergence is diagnosed
  * from==
  *
  * Two nodes disagreeing about a block agree that it is invalid and disagree
  * about which rule refused it far less often than they disagree about whether
  * any rule did. A single refusal carrying no reason makes the second case
  * indistinguishable from the first.
  *
  * The published corpus names its expectations the same way -- `INVALID_GASLIMIT`
  * and `INVALID_BASEFEE_PER_GAS` are separate expectations on separate blocks --
  * so the reasons here are what a fixture's expectation is compared against
  * rather than an internal convenience.
  */
enum HeaderFault:

  /** A block under a fee market whose stated charge is not the one its parent
    * requires.
    */
  case BaseFeeMismatch(stated: UInt256, required: UInt256)

  /** A block under a fee market carrying no charge at all. */
  case BaseFeeMissing

  /** A block below any fee market carrying a charge. */
  case BaseFeeUnexpected(stated: UInt256)

  /** A block whose gas limit moves further from its parent's than one step
    * allows, or falls under the floor.
    */
  case GasLimitOutOfBounds(stated: BigInt, comparedAgainst: BigInt)

  /** A block claiming to have used more gas than it allowed itself. */
  case GasUsedAboveLimit(used: BigInt, limit: BigInt)

  /** A block no later than the one it builds on. */
  case TimestampNotAfterParent(stated: BigInt, parent: BigInt)

  /** A block that is not its parent's successor. */
  case NumberNotParentSuccessor(stated: BigInt, parent: BigInt)

  /** A parent whose gas limit is too small for a target to be taken from it.
    *
    * Reachable only from a parent that was never itself validated, and returned
    * rather than left to arithmetic: dividing a parent's limit by the elasticity
    * multiplier gives the target the charge is derived against, and a target of
    * zero is a division by zero rather than a wrong answer.
    */
  case ParentGasLimitBelowFloor(stated: BigInt, floor: BigInt)

  /** A charge whose derivation does not fit what a header can state.
    *
    * Reachable only from a parent header that was never itself validated: a
    * parent whose own charge is near the top of the range derives a successor
    * above it. Returned rather than raised, because this type's whole contract
    * is that a caller holding an unvalidated header gets an answer instead of an
    * exception -- and a header arriving from a peer is exactly that.
    */
  case BaseFeeNotRepresentable(derived: BigInt)

/** A header together with the rules its own height resolves to.
  *
  * ==One parameter where four invited a transposition==
  *
  * The rules a header is checked under are the rules ITS OWN height resolves to,
  * and the pairing is the caller's to get right. Passed as four loose arguments
  * -- two headers and two rule sets -- every transposition type-checks, and one
  * of them validates a header against the wrong fork's rules, which is a chain
  * split from a call-site mistake nothing reports.
  *
  * Pairing them does not make a mistake impossible: a caller can still pass the
  * two the wrong way round. It reduces four arrangements of which three are
  * wrong to two of which one is, and the survivor reads wrongly at the call
  * site.
  *
  * @param rules
  *   what `org.fukuii.chainspec.UpgradeSchedule` answers at this header's own
  *   activation point. **A caller resolving both headers at one height has
  *   defeated the transition rule** -- [[HeaderValidator]] recognizes the first
  *   block under a fee market by the two differing, and by nothing else.
  */
final case class Resolved(header: BlockHeader, rules: UpgradeRules)

/** What a fork decides about a header, checked against the header's parent.
  *
  * ==The first header rules this build validates, and deliberately not all of
  * them==
  *
  * Nothing here checked a header before this type existed. What it checks is
  * what the fee market brings: the charge a block states against the one its
  * parent requires, whether a charge is present at all, and the bound on how far
  * a block may move the gas limit -- which the fee market modifies at the one
  * block where a market begins, and which therefore could not be left unbuilt.
  *
  * **What is checked: succession, the gas figure against its own limit, the
  * gas-limit bound, and the charge.** Against `ethereum/execution-specs` @
  * `20f7f6271a` `forks/london/fork.py`'s `validate_header`, what remains there
  * is the extra-data cap, the difficulty, the seal, the commitments -- and the
  * PARENT HASH, at `:364-366`, which is the one a reader would expect to find
  * here.
  *
  * **The parent hash is deferred to the caller, and the reason is that the
  * caller has usually discharged it already.** A header is paired with its
  * parent by looking the parent up under `parentHash`, and a caller that did
  * that has performed the comparison by construction; one that paired them some
  * other way has not, and owes it. Checking it here would also cost this layer
  * an RLP encoding and a hash of the parent on every header, to re-derive
  * something the lookup already knew.
  *
  * **No claim is made that this list is exhaustive.** An enumeration that looks
  * complete invites a caller to treat this layer as sufficient for header
  * validity, and it is not.
  *
  * **Difficulty, the seal and ommers are not here.** Each is engine-shaped on
  * the evidence, and [[ConsensusEngine]] already records that each *"arrives
  * with the layer that validates the thing it governs"*. This layer validates a
  * fee market and a gas limit; the layer that validates a difficulty is not this
  * one, and building it here would be building against a requirement nothing
  * has. The shape admits them: each is a further rule over the same two headers,
  * and the seal in particular wants to be a collaborator rather than a member,
  * because verifying one needs an epoch-scoped cache measured in tens of
  * megabytes and a header-only pass must not have to hold one.
  *
  * **A future-timestamp tolerance is not here either, and that is a boundary
  * rather than a deferral.** Four clients across two lineages check a header's
  * timestamp against the local clock in the same place as the rules above:
  * `ethereum/go-ethereum-pow` @ `v1.10.26`
  * `consensus/ethash/consensus.go:275`, `erigontech/erigon` @ `776a380b1a` and
  * `ethereumclassic/core-geth` @ `4185df450` all reach one `ErrFutureBlock`,
  * and `besu-eth/besu` @ `fdf1247c6d` reaches it independently, wiring
  * `TimestampBoundedByFutureParameter` into the same rule set that carries its
  * gas-usage and gas-limit rules.
  *
  * **That agreement is what makes the rule look like one of these and is not
  * the argument against it.** The argument is that `ethereum/execution-specs` @
  * `20f7f6271a` carries no clock comparison anywhere in `validate_header`, and
  * that each of those four picks its own tolerance -- so the rule is not a
  * function of the chain, it alters no state root, and it is operator-tunable.
  * Four clients each choosing a different bound is stronger evidence for that
  * than agreement on one would be. It belongs to whichever layer decides what
  * this node is willing to accept rather than what the network declares valid.
  *
  * ==Two values are held here rather than resolved per fork, and the reason is
  * that they do not vary==
  *
  * The bound divisor and the floor under a gas limit are the same on every fork
  * of every network this project serves. `ethereum/execution-specs` @
  * `20f7f6271a` states them once as `LIMIT_ADJUSTMENT_FACTOR` and
  * `LIMIT_MINIMUM` in `forks/london/vm/gas.py:87-88`, and
  * `ethereum/go-ethereum-pow` @ `v1.10.26` as the package constants
  * `GasLimitBoundDivisor` and `MinGasLimit`. One client of five puts them on a
  * fork-resolved record and does not vary them there either, which is the same
  * evidence [[org.fukuii.chainspec.UpgradeRules]] already refused the extra-data
  * cap on.
  *
  * ==This is not a block validator, and the boundary is already drawn==
  *
  * `org.fukuii.execution.BlockProcessor` states it: everything that makes a
  * block invalid *"its header, its seal, its ommers, its commitments -- is
  * decided by layers this project has not built, and each will have its own
  * reasons rather than more cases here."* Four concerns, and this is one. In
  * particular nothing here compares a commitment: a state root, a receipts root
  * or a gas-used figure is checked against what execution PRODUCED, which needs
  * an executed block and would make this a block validator rather than a header
  * one.
  */
object HeaderValidator:

  /** The quotient a gas limit's permitted step is sized by.
    *
    * `ethereum/execution-specs` @ `20f7f6271a` `forks/london/vm/gas.py:87` and
    * `ethereum/go-ethereum-pow` @ `v1.10.26` `params/protocol_params.go:22`.
    */
  val GasLimitBoundDivisor: BigInt = BigInt(1024)

  /** The floor no gas limit may fall under.
    *
    * Same two sources, `forks/london/vm/gas.py:88` and
    * `params/protocol_params.go:23`.
    */
  val MinGasLimit: BigInt = BigInt(5000)

  /** Checks `header` against `parent`, under the rules each resolves to.
    *
    * ==Both rule sets, because the transition is where they differ==
    *
    * A caller passes what the schedule resolved for each block rather than one
    * rule set for both. That is what makes the first block under a fee market
    * recognizable without a height: its parent resolves to rules with no market
    * and it resolves to rules with one. A height here would encode one
    * activation axis and duplicate a fact
    * [[org.fukuii.chainspec.UpgradeSchedule]] already owns.
    */
  /** ==The order differs from the specification's, and it costs a reason==
    *
    * `ethereum/execution-specs` @ `20f7f6271a` `forks/london/fork.py`'s
    * `validate_header` runs the gas figure, then the charge, then succession.
    * This runs succession first, because a pair that is not a parent and a child
    * has no meaningful answer to the other three. **The cost is the same one
    * `org.fukuii.execution.TransactionAdmission` records for its own composed
    * order: a header breaking two rules is refused by whichever runs first, so
    * the REASON differs from the specification's while the verdict does not.**
    *
    * **Nothing here depends on that order for its totality**, which is worth
    * stating because it briefly did: the derivation's division by a parent's
    * target was safe only because the gas-limit bound happened to refuse a tiny
    * parent first, so reordering would have reopened a crash. The derivation now
    * refuses such a parent itself.
    */
  def validate(block: Resolved, parent: Resolved): Either[HeaderFault, Unit] =
    for
      _ <- checkSuccession(block.header, parent.header)
      _ <- checkGasUsed(block.header)
      _ <- checkGasLimit(block, parent)
      _ <- checkBaseFee(block, parent)
    yield ()

  /** A block is its parent's successor and is later than it.
    *
    * Two rules that need neither a fork's rules nor an executed block, and that
    * the field checks in the same place as the rest: `ethereum/execution-specs`
    * @ `20f7f6271a` `forks/london/fork.py:347` and `:349`, inside
    * `validate_header`.
    */
  private def checkSuccession(header: BlockHeader, parent: BlockHeader): Either[HeaderFault, Unit] =
    val stated = header.number.toBigInt
    val before = parent.number.toBigInt
    if header.timestamp.toBigInt <= parent.timestamp.toBigInt then
      Left(HeaderFault.TimestampNotAfterParent(header.timestamp.toBigInt, parent.timestamp.toBigInt))
    else if stated != before + 1 then Left(HeaderFault.NumberNotParentSuccessor(stated, before))
    else Right(())

  /** A block did not use more gas than it allowed itself.
    *
    * ==Two fields of one header, and it is NOT the commitment this layer defers==
    *
    * Checking a header's gas figure against what execution PRODUCED needs an
    * executed block and is a block validator's. Checking it against the limit
    * stated beside it needs nothing at all, and the distinction is worth stating
    * because collapsing the two is how this rule went missing: the reason for
    * deferring the first does not reach the second.
    *
    * `ethereum/execution-specs` @ `20f7f6271a` `forks/london/fork.py:327`;
    * `ethereum/EIPs` @ `dbfa6bee83` `EIPS/eip-1559.md:173`;
    * `ethereum/go-ethereum-pow` @ `v1.10.26`
    * `consensus/ethash/consensus.go:293`.
    */
  private def checkGasUsed(header: BlockHeader): Either[HeaderFault, Unit] =
    val used = header.gasUsed.toBigInt
    val limit = header.gasLimit.toBigInt
    if used > limit then Left(HeaderFault.GasUsedAboveLimit(used, limit)) else Right(())

  /** The bound on how far a block moves the gas limit, with the parent's limit
    * scaled where this block is the first under a fee market.
    *
    * ==The scaling is the whole of the transition rule==
    *
    * A fee market halves the limit to reach a target, so the block introducing
    * one doubles its limit to keep the target where it was. Comparing that
    * doubling against an unscaled parent would reject it as a step far outside
    * the bound; skipping the bound entirely at that block would accept any limit
    * at all. Both are wrong and the corpus separates all three readings.
    *
    * ==One source disagrees, and the rest of the field does not==
    *
    * `ethereum/execution-specs` @ `20f7f6271a` does NOT run this bound at the
    * transition block: `forks/london/fork.py` calls `check_gas_limit` only from
    * inside `calculate_base_fee_per_gas`, and `validate_header` skips that call
    * where the block is the first under the market. **Every other source read
    * runs it against a scaled parent**, and they are listed rather than counted,
    * because a count over a list mixing a specification with clients has to say
    * which of the two it counts -- the proposal's own specification, which
    * applies
    * the multiplier to `parent_gas_limit` at the fork block with a comment
    * saying why and leaves the assertions unconditional; `go-ethereum-pow` @
    * `v1.10.26` `consensus/misc/eip1559.go:34-38`; `besu-eth/besu` @
    * `fdf1247c6d` `GasLimitRangeAndDeltaValidationRule`; `NethermindEth/nethermind`
    * @ `b92e2a4719` `Eip1559GasLimitAdjuster`; `erigontech/erigon` @ `776a380b1a`
    * `VerifyParentGasLimit`; `ethereumclassic/core-geth` @ `4185df450`.
    *
    * **A published fixture settles it rather than leaving it to a count of
    * clients.** `ethereum/legacytests` @ `1f581b8c`,
    * `Cancun/BlockchainTests/TransitionTests/bcBerlinToLondon/BerlinToLondonTransition.json`
    * runs a market beginning at block 5 over a parent limit of 3,141,592. The
    * accepted block states exactly twice that; three siblings at the same height
    * are required to be rejected, two of them one unit outside the bound in each
    * direction. Under an unscaled parent the accepted block is rejected; under
    * no bound at all the three rejected ones are accepted.
    *
    * **The departure is stated and reversible.** It ends if that specification
    * gains a transition fixture, or moves its `check_gas_limit` call out of the
    * base-fee derivation.
    *
    * ==The comparison is strict on both sides, and the fixture does NOT settle
    * that half==
    *
    * A limit exactly one step away is refused, not accepted, because
    * `check_gas_limit` writes `>=` and `<=` against the sum and the difference.
    * **The specification's text is the whole of the evidence for it.** The
    * fixture's rejected siblings sit at a delta of 6,136 against a permitted
    * 6,135, so they are refused under `>=` and under `>` alike -- the values
    * that would separate the two readings, 6,289,319 and 6,277,049, are absent
    * from it.
    *
    * **So the fixture settles which bound is taken and not how the comparison
    * is written.** Reading it as evidence for the second would be citing a
    * corpus as agreeing where it could not have disagreed.
    */
  private def checkGasLimit(block: Resolved, parent: Resolved): Either[HeaderFault, Unit] =
    val parentLimit = parent.header.gasLimit.toBigInt
    val comparedAgainst =
      if beginsFeeMarket(block.rules, parent.rules) then parentLimit * elasticityOf(block.rules)
      else parentLimit
    val step = comparedAgainst / GasLimitBoundDivisor
    val stated = block.header.gasLimit.toBigInt
    if stated >= comparedAgainst + step || stated <= comparedAgainst - step || stated < MinGasLimit then
      Left(HeaderFault.GasLimitOutOfBounds(stated, comparedAgainst))
    else Right(())

  /** The charge a block states, against the one its parent requires.
    *
    * ==Presence and absence are both rules, and the absence half is the one a
    * naive check drops==
    *
    * A header under a fork with a fee market must carry a charge; a header under
    * a fork without one must not. Checking only the first admits a block that
    * carries a base fee before any market exists, which every client read here
    * refuses.
    */
  private def checkBaseFee(block: Resolved, parent: Resolved): Either[HeaderFault, Unit] =
    (block.rules.header.feeMarket, block.header.baseFeePerGas) match
      case (None, None)                 => Right(())
      case (None, Some(stated))         => Left(HeaderFault.BaseFeeUnexpected(stated))
      case (Some(_), None)              => Left(HeaderFault.BaseFeeMissing)
      case (Some(market), Some(stated)) =>
        baseFeeFor(parent, market, block.rules).flatMap { required =>
          if stated == required then Right(()) else Left(HeaderFault.BaseFeeMismatch(stated, required))
        }

  /** What a block's charge must be, derived from its parent.
    *
    * ==The first block under a market states the market's opening charge==
    *
    * There is no parent under the market to derive from, so the value is read
    * rather than computed. Every source agrees, and `besu-eth/besu` @
    * `fdf1247c6d` additionally treats it as a value a chain may set at genesis,
    * which is why it is a member of the market rather than a constant here.
    *
    * ==Otherwise the charge follows the parent's gas use against its target==
    *
    * The target is the parent's limit divided by the elasticity multiplier.
    * Above it the charge rises, below it the charge falls, and at it the charge
    * is unchanged -- three arms, and the corpus exercises all three.
    *
    * **The rise is floored at one and the fall is not.** A parent barely over
    * its target computes a rise that rounds to zero, and the specification takes
    * `max(1, ...)` there so the charge cannot stall while blocks are over
    * target; the fall has no such floor and is allowed to round to nothing.
    * `ethereum/execution-specs` @ `20f7f6271a` `forks/london/fork.py:268` and
    * `:282` are the two arms, and the asymmetry between them is deliberate
    * rather than an oversight to normalize.
    *
    * ==It reads the parent and never the block==
    *
    * Which is the point of a derivation: what a block MUST state cannot depend
    * on what it DOES state. The signature carries no header for that reason, so
    * a future edit reaching for one has to add it and say why.
    */
  private def baseFeeFor(parent: Resolved, market: FeeMarket, rules: UpgradeRules): Either[HeaderFault, UInt256] =
    if beginsFeeMarket(rules, parent.rules) then Right(market.initialBaseFee)
    else
      val parentBaseFee = parent.header.baseFeePerGas.map(_.toBigInt).getOrElse(BigInt(0))
      val parentUsed = parent.header.gasUsed.toBigInt
      val parentLimit = parent.header.gasLimit.toBigInt
      val parentTarget = parentLimit / market.elasticityMultiplier
      if parentLimit < MinGasLimit then Left(HeaderFault.ParentGasLimitBelowFloor(parentLimit, MinGasLimit))
      else
        val derived =
          if parentUsed == parentTarget then parentBaseFee
          else if parentUsed > parentTarget then
            val delta = parentBaseFee * (parentUsed - parentTarget) / parentTarget / market.maxChangeDenominator
            parentBaseFee + delta.max(BigInt(1))
          else
            val delta = parentBaseFee * (parentTarget - parentUsed) / parentTarget / market.maxChangeDenominator
            parentBaseFee - delta
        UInt256.fromBigInt(derived).left.map(_ => HeaderFault.BaseFeeNotRepresentable(derived))

  /** Whether this block is the first under a fee market.
    *
    * Answered by comparing the two resolved rule sets rather than against a
    * height, which is what keeps this correct on both activation axes.
    */
  private def beginsFeeMarket(rules: UpgradeRules, parentRules: UpgradeRules): Boolean =
    rules.header.feeMarket.isDefined && parentRules.header.feeMarket.isEmpty

  private def elasticityOf(rules: UpgradeRules): BigInt =
    rules.header.feeMarket.map(_.elasticityMultiplier).getOrElse(BigInt(1))
