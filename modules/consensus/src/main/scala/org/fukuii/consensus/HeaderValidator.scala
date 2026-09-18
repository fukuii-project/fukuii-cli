package org.fukuii.consensus

import org.fukuii.bytes.{Hash, UInt256, UInt64}
import org.fukuii.chainspec.{BlobSchedule, FeeMarket, HeaderConstants, UpgradeRules}
import org.fukuii.evm.BlobGas
import org.fukuii.types.{BlockHeader, BlockNonce, Seal}

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

  /** A block allowing itself more gas than any block may.
    *
    * The maximum is carried beside the figure so the reason reads whole, as
    * [[ExtraDataAboveLimit]]'s does; it is [[HeaderValidator.MaxGasLimit]] on
    * every fork.
    */
  case GasLimitAboveMaximum(stated: BigInt, maximum: BigInt)

  /** A block claiming to have used more gas than it allowed itself. */
  case GasUsedAboveLimit(used: BigInt, limit: BigInt)

  /** A block whose extra data is longer than its consensus mechanism allows.
    *
    * ==A header fault that [[HeaderValidator]] never reports==
    *
    * The bound is the mechanism's, so [[ConsensusEngine.validateHeader]] is what
    * checks it and this is only the vocabulary it reports in. The clients read
    * for it vary the rule by engine rather than by fork: `besu-eth/besu` @
    * `b330564a94` wires `ExtraDataMaxLengthValidationRule(BlockHeader.MAX_EXTRA_DATA_BYTES)`
    * into its mainnet and merge header rules and a `CliqueExtraDataValidationRule`
    * into Clique's, and among the chain specifications `NethermindEth/nethermind`
    * @ `3a98e0818` ships in `src/Nethermind/Chains/`, each of the four Clique
    * ones states a `maximumExtraDataSize` of `0xffff` and each ethash and
    * authority-round one `0x20`. That is a reading of what ships: a test
    * specification elsewhere in the same tree states `0xffff` under ethash.
    *
    * The limit is carried beside the length because a length alone does not say
    * which engine refused it.
    */
  case ExtraDataAboveLimit(length: Int, limit: Int)

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

  /** A block under a fork that fixes the difficulty, stating something else.
    *
    * The stated value is carried and the required one is not: it is the
    * constant zero under every fork that has this rule, so repeating it in the
    * reason would state a fact the type already fixes.
    */
  case DifficultyNotFixed(stated: UInt256)

  /** A block whose stated difficulty is not the one its parent requires.
    *
    * ==A header fault that [[HeaderValidator]] never reports==
    *
    * The formula is the block's own mechanism's, so
    * [[ConsensusEngine.validateHeader]] is what checks it, and this is only the
    * vocabulary it reports in -- as [[ExtraDataAboveLimit]] is. The required
    * value is carried beside the stated one because a formula over a parent is
    * what separates this from [[DifficultyNotFixed]], and the reason is read
    * from the pair: `ethereum/go-ethereum-pow` @ `v1.10.26` refuses with
    * *"invalid difficulty: have %v, want %v"*
    * (`consensus/ethash/consensus.go:283-287`).
    */
  case DifficultyMismatch(stated: UInt256, required: UInt256)

  /** A block under a fork that fixes the seal's nonce, carrying a different
    * one.
    *
    * ==The seal the fork does not use is a separate reason==
    *
    * A header sealed some other way than by a digest and a nonce cannot state a
    * wrong nonce, because it has no nonce field to state one in. That is
    * [[SealShapeUnexpected]], and collapsing the two would report a header
    * carrying an authority-round seal as one whose nonce is non-zero, which is
    * a diagnosis a reader cannot act on.
    */
  case NonceNotFixed(stated: BlockNonce)

  /** A block under a fork that fixes the ommers commitment, committing to some
    * other list.
    *
    * **This is a header rule and it is not the whole of the ommer rule.** The
    * body carrying no ommers is checked where a body is available, which is not
    * this layer -- see [[HeaderValidator]].
    */
  case OmmersNotEmpty(stated: Hash)

  /** A header whose seal is not the shape the fork's constant rules are stated
    * over.
    *
    * EIP-3675 fixes a `nonce`, and a header sealed by an authority round
    * carries a step and a signature in those two slots rather than a digest and
    * a nonce. Such a header is not one whose nonce is wrong; it is one the rule
    * cannot be applied to at all, and saying so is what keeps this layer from
    * reporting a shape mismatch as a value mismatch.
    */
  case SealShapeUnexpected

  /** A block at a fork that commits to withdrawals, carrying no such
    * commitment.
    */
  case WithdrawalsRootMissing

  /** A block below any withdrawals proposal, carrying a commitment over them.
    */
  case WithdrawalsRootUnexpected(stated: Hash)

  /** A charge whose derivation does not fit what a header can state.
    *
    * Reachable only from a parent header that was never itself validated: a
    * parent whose own charge is near the top of the range derives a successor
    * above it. Returned rather than raised, because this type's whole contract
    * is that a caller holding an unvalidated header gets an answer instead of an
    * exception -- and a header arriving from a peer is exactly that.
    */
  case BaseFeeNotRepresentable(derived: BigInt)

  /** A block at a fork that accounts for blob gas, carrying no such account.
    *
    * One reason for the pair rather than one each, because
    * `org.fukuii.types.BlobGasTail` holds both fields on one link: a header
    * stating one of them and not the other is unencodable rather than invalid.
    */
  case BlobGasMissing

  /** A block below any blob-gas proposal, carrying an account of it.
    *
    * The excess alone names the link, for [[BlobGasMissing]]'s reason: the two
    * fields are one link and cannot be present separately, so reporting one
    * reports both.
    */
  case BlobGasUnexpected(excessBlobGas: UInt64)

  /** A block whose stated excess is not the one its parent requires. */
  case ExcessBlobGasMismatch(stated: UInt64, required: BigInt)

  /** A block whose stated spend is not a whole number of blobs.
    *
    * Blob gas is bought a blob at a time, so a figure between two multiples of
    * the per-blob cost names a quantity no block can have carried.
    */
  case BlobGasUsedNotWholeBlobs(stated: UInt64)

  /** A block whose stated spend is more blob gas than its fork allows.
    *
    * The limit is carried rather than only the figure, because what makes the
    * spend too large is the fork's schedule and not the number alone -- the same
    * spend is within the limit at a later fork.
    */
  case BlobGasUsedAboveLimit(stated: UInt64, limit: BigInt)

  /** An excess whose derivation does not fit what a header can state.
    *
    * Reachable only from a parent that was never itself validated, for the
    * reason [[BaseFeeNotRepresentable]] is: two fields near the top of the range
    * sum past it, and a header cannot state the result. Returned rather than
    * raised, because a header arriving from a peer is exactly the caller this
    * type promises an answer to.
    */
  case ExcessBlobGasNotRepresentable(derived: BigInt)

  /** A block at a fork that states the root of the beacon block its parent was
    * built against, stating none.
    */
  case ParentBeaconBlockRootMissing

  /** A block below any beacon-root proposal, stating one. */
  case ParentBeaconBlockRootUnexpected(stated: Hash)

  /** A block at a fork that commits to the execution-layer requests it
    * produced, stating no commitment.
    */
  case RequestsHashMissing

  /** A block below any execution-layer-requests proposal, stating a
    * commitment.
    */
  case RequestsHashUnexpected(stated: Hash)

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
  * gas limit's maximum and its bound, the charge, the fields a fork fixes to
  * constants, whether a commitment over the block's withdrawals is present
  * where its fork requires one, and the blob-gas excess against the one its
  * parent requires.**
  * Against `ethereum/execution-specs` @ `20f7f6271a` `forks/london/fork.py`'s
  * `validate_header`, what remains there is the extra-data cap, the difficulty,
  * the seal, the commitments -- and the PARENT HASH, at `:364-366`, which is the
  * one a reader would expect to find here.
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
  * **Difficulty, the seal and ommers are not VERIFIED here, and three of them
  * are now COMPARED here.** The distinction is the whole content of EIP-3675 as
  * this layer meets it, and reading the two as one is what would put the wrong
  * rule in the wrong place.
  *
  * Verifying a difficulty means running a targeting formula over a parent;
  * verifying a seal means hashing a header against an epoch-scoped cache
  * measured in tens of megabytes; verifying ommers means holding a body and
  * several ancestors. Each is engine-shaped on the evidence, and **none of the
  * three happens here.** A difficulty rule and a seal rule are the block's own
  * mechanism's: [[ConsensusEngine.validateHeader]] runs one or names it as an
  * [[EngineRule]] it did not run, so a header-only pass here never holds a
  * seal's cache. Ommer validation is built nowhere yet, and
  * [[BlockValidator]] answers a block whose body carries ommers as undecided
  * before it runs, wherever no earlier comparison refuses it.
  *
  * **What EIP-3675 adds is not verification of any of the three but the removal
  * of it, replaced by a comparison against a constant** -- *"Remove verification
  * of the block's `difficulty` value with respect to the difficulty formula.
  * Remove verification of the block's `nonce` and `mixHash` values with respect
  * to the Ethash function"* (`ethereum/EIPs` @ `dbfa6bee8` (2026-08-26),
  * `EIPS/eip-3675.md:99-100`). A comparison against a constant needs no engine,
  * no cache and no parent, so it is a header rule in the strict sense this layer
  * already holds, and it is gated on
  * [[org.fukuii.chainspec.HeaderRules.constants]] rather than on an engine's
  * identity.
  *
  * **Two sources put those comparisons exactly where this layer sits.**
  * `ethereum/execution-specs` @ `20f7f6271a` (2026-08-26) runs all three inside
  * `validate_header` -- `src/ethereum/forks/paris/fork.py:324`, `:326` and
  * `:328` -- the same function this layer implements a subset of. `besu-eth/besu`
  * @ `fdf1247c6d` (2026-08-26) adds `ConstantOmmersHashRule`, `NoNonceRule` and
  * `NoDifficultyRule` to the header-validation builder its fork-resolved
  * specification wires, in
  * `MainnetBlockHeaderValidator.mergeBlockHeaderValidator`, alongside the
  * ancestry, gas-usage, gas-limit and base-fee rules this layer already carries.
  *
  * ==besu ships each of those three rules TWICE, and the copy this layer follows
  * is the unconditional one==
  *
  * Worth recording because the other copy is the one a reader finds first and it
  * would be the wrong model. Its `consensus/merge` package carries a
  * `NoDifficultyRule` and a `NoNonceRule` that consult the accumulated work
  * against a terminal total difficulty and exempt the terminal block, and its
  * `ethereum/core` package carries a `NoDifficultyRule` and a `NoNonceRule` that
  * compare the constant and nothing else. **The fork-resolved definition wires
  * the second.** The conditional pair exists for a node deciding the transition
  * as it happens, which is a question a schedule resolved at a known height has
  * already answered -- see
  * [[org.fukuii.chainspec.Upgrade.RetrospectiveRuleChange]] for why the height
  * is knowable afterwards and was not before.
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
  * ==Three values are held here rather than resolved per fork, and the reason is
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
  * cap on. The maximum is the third: [[MaxGasLimit]] records that none of the
  * sources read holds it on a fork's record or a network's.
  *
  * ==This is not a block validator, and [[BlockValidator]] is==
  *
  * [[BlockValidator]] runs this once the block names its parent, and ahead of
  * the block's own mechanism's header rules, the body's commitments and the
  * block itself, refusing under a [[BlockFault]] of its own for each. In
  * particular nothing here compares a commitment: a state root, a receipts
  * root or a gas-used figure is checked against what execution PRODUCED, which
  * needs an executed block and would make this a block validator rather than a
  * header one.
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

  /** The most gas any block may allow itself: `2^63 - 1`.
    *
    * Stated as `0x7fffffffffffffff` by `ethereum/go-ethereum` @ `02872e9ef`
    * (`params/protocol_params.go:28`), `ethereum/go-ethereum-pow` @ `v1.10.26`
    * (`params/protocol_params.go:24`), `ethereumclassic/core-geth` @
    * `4185df450` (`params/vars/protocol_params.go:66`) and `erigontech/erigon` @
    * `ab8e9fde7` (`execution/protocol/params/protocol.go:32`); as
    * `0x7FFFFFFFFFFFFFFF` by `NethermindEth/nethermind` @ `3a98e0818`
    * (`Nethermind.Consensus/Validators/HeaderValidator.cs:176`); and checked
    * against `0x7fffffffffffffff` by `ethereum/retesteth` @ `949f9b21`, the tool
    * that filled the published case
    * (`retesteth/session/ToolBackend/Verification.cpp:315-316`). None of them
    * sits on a fork's record or a network's.
    *
    * `Long.MaxValue` is that figure by the JVM's own definition, and
    * `HeaderValidatorSpec` asserts it against both the power of two and the
    * literal those sources write.
    */
  val MaxGasLimit: BigInt = BigInt(Long.MaxValue)

  /** The blob gas one blob costs, which no fork varies.
    *
    * ==Re-exported rather than defined, and the distinction is the point==
    *
    * The figure and its evidence are `org.fukuii.evm.BlobGas.PerBlob`'s. It
    * lives a module below this one because a header rule is not its only
    * reader: admission prices a transaction's blobs from the same number and
    * block processing accumulates them, in `org.fukuii.execution`, which cannot
    * see this module and which this module cannot see either. **A second
    * definition here would be one number with two homes**, and a fork moving it
    * would move one of them.
    *
    * The name is kept for the rules below that read it, and the value is not.
    */
  val BlobGasPerBlob: BigInt = BlobGas.PerBlob

  /** What a header commits to when it includes no ommers.
    *
    * ==Re-exported rather than defined, for [[BlobGasPerBlob]]'s reason==
    *
    * The value and its derivation are
    * [[org.fukuii.types.BlockHeader.EmptyOmmersHash]]'s, a module below this one,
    * because this rule is not its only reader: a payload's translation into a
    * header reads it too, from a module that cannot see this one, and the
    * proof-of-work engine's header rule reads the same definition.
    * `ethereum/EIPs` @ `dbfa6bee8` (2026-08-26),
    * `EIPS/eip-3675.md:83-87` is the statement this rule compares against, and
    * `HeaderValidatorSpec` holds this name to the literal that table prints.
    */
  val EmptyOmmersHash: Hash = BlockHeader.EmptyOmmersHash

  /** The rules every mechanism shares, in the order they run.
    *
    * The one list [[validate]] and [[faults]] both read, so a rule added here
    * reaches both, and no rule can be enforced by one and reported by the other.
    */
  private val rules: Vector[(Resolved, Resolved) => Either[HeaderFault, Unit]] =
    Vector(
      (block, parent) => checkSuccession(block.header, parent.header),
      (block, _) => checkGasUsed(block.header),
      (block, _) => checkGasLimitMaximum(block.header),
      checkGasLimit,
      checkBaseFee,
      (block, _) => checkConstants(block),
      (block, _) => checkWithdrawalsRoot(block),
      checkBlobGas,
      (block, _) => checkParentBeaconBlockRoot(block),
      (block, _) => checkRequestsHash(block)
    )

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
    *
    * ==The order differs from the specification's, and it costs a reason==
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
    rules.iterator.map(rule => rule(block, parent)).collectFirst { case Left(fault) => fault }.toLeft(())

  /** Every shared rule `block` breaks against `parent`, in [[validate]]'s order.
    *
    * ==[[validate]] refuses with the first of these==
    *
    * Both read one list of rules: [[validate]] stops at the first that refuses,
    * and this reads every one. `HeaderValidatorPropSpec` holds the two answers
    * together over headers breaking each suffix of the rules and each rule alone.
    *
    * ==For a caller holding a reason from elsewhere==
    *
    * A header breaking two rules is refused by whichever runs first, so a reason
    * stated by another implementation can name the other one while agreeing
    * that the header is invalid. This answers whether the header breaks the rule
    * a stated reason names, without re-deriving any rule outside this object.
    *
    * Each rule answers on its own: none reads another's outcome, which
    * [[validate]]'s order note states. **Three rules are made of several
    * comparisons a header can break together, and each answers with the first it
    * breaks**: succession (the timestamp, then the number), the constants (the
    * difficulty, the ommers commitment, then the seal) and the blob-gas account
    * (whole blobs, the maximum, then the excess). So a header breaking two
    * comparisons of one rule is listed under the first alone, and a caller
    * holding a reason for the second finds no fault carrying it -- a divergence
    * that caller counts, never an agreement it should not reach.
    *
    * **The mechanism's own rules are not here**, and a caller cannot add them:
    * [[ConsensusEngine.validateHeader]] is reached only once every rule here has
    * accepted the header, and an override may rely on that, so it has no answer
    * over a header this lists a fault for.
    */
  def faults(block: Resolved, parent: Resolved): Vector[HeaderFault] =
    rules.map(rule => rule(block, parent)).collect { case Left(fault) => fault }

  /** A header states a commitment over its block's withdrawals exactly where
    * its fork requires one.
    *
    * ==Presence and absence, and deliberately not the value==
    *
    * The pair is the same one [[checkBaseFee]] states and is checked the same
    * way, because a header alone is enough to settle it: a fork with the
    * withdrawals proposal requires the field and a fork below it forbids it.
    * `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) resolves exactly this pair per
    * fork, in `WithdrawalsValidator.ProhibitedWithdrawals.validateWithdrawalsRoot`
    * -- *"withdrawalsRoot must be null when Withdrawals are prohibited"* -- and
    * its `AllowedWithdrawals` converse.
    *
    * **What the root must BE is not asked here, and this is the boundary this
    * object already draws rather than a new one.** That comparison needs the
    * block's withdrawals, so it belongs to a caller holding a body:
    * [[BlockValidator]] derives the root from the body's own list and compares
    * it, and refuses a body whose list is present where the header commits to
    * none or absent where it commits to one. So a caller that has validated the
    * block is not relying on this check, and a node that has only a header is
    * not left without one.
    */
  private def checkWithdrawalsRoot(block: Resolved): Either[HeaderFault, Unit] =
    (block.rules.header.carriesWithdrawalsRoot, block.header.withdrawalsRoot) match
      case (false, None)         => Right(())
      case (false, Some(stated)) => Left(HeaderFault.WithdrawalsRootUnexpected(stated))
      case (true, None)          => Left(HeaderFault.WithdrawalsRootMissing)
      case (true, Some(_))       => Right(())

  /** A header accounts for blob gas exactly where its fork does, and states the
    * excess its parent requires.
    *
    * ==Presence and absence, then a derivation, which is [[checkBaseFee]]'s
    * shape with one difference==
    *
    * The pair is the same one that object already checks twice, and the fields
    * arrive as one link so presence is one question rather than two. What
    * differs is the transition: a fee market states an opening charge, because
    * there is no parent under the market to derive from. This has no opening
    * value at all -- the derivation is total over a parent with no blob fields,
    * reading both as zero, and answers zero there because zero is below every
    * target. `ethereum/EIPs` @ `d2a64c2d4` (2026-09-11),
    * `EIPS/eip-4844.md:162` says so in terms: *"For the first post-fork block,
    * both `parent.blob_gas_used` and `parent.excess_blob_gas` are evaluated as
    * `0`."*
    *
    * **So this rule needs no `beginsBlobSchedule`, and adding one would be a
    * second source of truth for something the arithmetic already settles.** Both
    * production clients read the parent's absent fields as zero rather than
    * comparing rule sets -- `ethereum/go-ethereum` @ `02872e9ef`
    * `consensus/misc/eip4844/eip4844.go:139-141` guards on
    * `parent.ExcessBlobGas != nil`, and `besu-eth/besu` @ `b330564a9`
    * `ethereum/core/.../headervalidationrules/BlobGasValidationRule.java:53-54`
    * takes `.orElse(0L)` for each.
    *
    * ==Two BOUNDS on the block's own spend, which the specification reaches by
    * another route==
    *
    * **Neither the executable specification nor the document states these as
    * header rules.** `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11)
    * `src/ethereum/forks/cancun/fork.py:315-369` is the whole of
    * `validate_header`, and its only blob clause is the excess (`:339-341`). The
    * maximum is enforced one transaction at a time while the block executes --
    * `:432-439` takes `MAX_BLOB_GAS_PER_BLOCK - block_output.blob_gas_used` as
    * what remains and refuses a transaction asking for more -- and the header's
    * own figure is compared against the accumulated total at `:241`.
    * `ethereum/EIPs` @ `d2a64c2d4` `EIPS/eip-4844.md:281-287` has the same
    * shape: accumulate, assert the total is within the maximum, assert the
    * header matches it.
    *
    * **Both production clients hoist both bounds to the header.**
    * `ethereum/go-ethereum` @ `02872e9ef`
    * `consensus/misc/eip4844/eip4844.go:112-117` and `besu-eth/besu` @
    * `b330564a9`
    * `ethereum/core/.../headervalidationrules/BlobGasValidationRule.java:67-77`
    * each check that the figure is a whole number of blobs and that it is no
    * more than the fork's maximum, from the header alone.
    *
    * ==Why hoisting them is sound rather than merely conventional==
    *
    * The accumulated total is a sum of per-transaction figures each of which is
    * `PER_BLOB` times a blob count (`forks/cancun/vm/gas.py:431-434`), so it is
    * always a whole number of blobs, and the per-transaction guard above bounds
    * it by the maximum. A header failing either bound therefore cannot equal the
    * total the specification computes for ANY body, so refusing it here refuses
    * a strict subset of what the specification refuses -- no later than its
    * per-transaction guard for a body whose blobs exceed the maximum, which never
    * reaches `:241`,
    * and no later than `:241` for any other -- earlier, and without one.
    * **That is what makes this an earlier refusal and not a stricter rule**: no
    * block the specification accepts is refused by either bound.
    *
    * ==The order is this build's, because the two clients disagree about it==
    *
    * go-ethereum runs the maximum, then the whole-blob check, then the excess;
    * besu runs the excess, then the whole-blob check, then the maximum. This
    * runs the two header-only bounds before the parent-dependent derivation,
    * which is the locality [[validate]] already orders by, and the whole-blob
    * check before the maximum, because a figure that is not a whole number of
    * blobs has no blob count to compare against one. **The cost is the one
    * [[validate]] already records**: a header breaking two of these is refused
    * by whichever runs first, so the REASON can differ from another client's
    * while the verdict does not.
    *
    * ==What remains deferred is the commitment, and NOT for the reason the
    * other commitments are==
    *
    * A block's stated `blobGasUsed` against what its transactions actually
    * carried is not a header rule and is not checked here. **It does not need
    * an executed block either**, which is what separates it from every other
    * commitment this layer defers: a state root, a receipts root and a gas-used
    * figure are compared against what execution PRODUCED, while a blob spend is
    * a count of the commitments each transaction states --
    * `org.fukuii.evm.BlobGas.spentBy` is the whole derivation and it reads no
    * result. So what it needs is a layer holding a header and a BODY together,
    * which is a weaker thing than an executed block and a different one --
    * [[BlockValidator]] is that layer, and compares it before running anything.
    *
    * `CancunBlobGasCertificationSpec` measures it rather than asserting it: the
    * forty-two blocks in this corpus that no bound above reaches are each
    * decided by their own decoded body, with the hundred and seventy-five the
    * corpus accepts left alone.
    */
  private def checkBlobGas(block: Resolved, parent: Resolved): Either[HeaderFault, Unit] =
    // Matched on the excess alone rather than on both fields, because
    // `org.fukuii.types.BlobGasTail` holds the pair and a header carrying one
    // without the other is unencodable. Asking twice would add two arms for
    // states no header can reach.
    (block.rules.header.blobSchedule, block.header.excessBlobGas) match
      case (None, None)                   => Right(())
      case (None, Some(stated))           => Left(HeaderFault.BlobGasUnexpected(stated))
      case (Some(_), None)                => Left(HeaderFault.BlobGasMissing)
      case (Some(schedule), Some(stated)) =>
        checkBlobGasUsed(block.header.blobGasUsed, schedule)
          .flatMap(_ => checkExcessBlobGas(parent, schedule, stated))

  /** A block's own spend is a whole number of blobs, and no more than its fork
    * allows.
    *
    * The spend arrives as an option only because it shares a link with the
    * excess. A caller reaching here has established that link is present, so an
    * absent spend is the link being absent and answers as such rather than
    * passing unchecked.
    */
  private def checkBlobGasUsed(spent: Option[UInt64], schedule: BlobSchedule): Either[HeaderFault, Unit] =
    spent.toRight(HeaderFault.BlobGasMissing).flatMap { used =>
      val limit = schedule.maxBlobs * BlobGasPerBlob
      if used.toBigInt % BlobGasPerBlob != 0 then Left(HeaderFault.BlobGasUsedNotWholeBlobs(used))
      else if used.toBigInt > limit then Left(HeaderFault.BlobGasUsedAboveLimit(used, limit))
      else Right(())
    }

  /** A block states the excess its parent requires. */
  private def checkExcessBlobGas(
      parent: Resolved,
      schedule: BlobSchedule,
      stated: UInt64
  ): Either[HeaderFault, Unit] =
    val required = excessBlobGasFor(parent, schedule)
    UInt64.fromBigInt(required) match
      case Left(_)     => Left(HeaderFault.ExcessBlobGasNotRepresentable(required))
      case Right(fits) =>
        if stated == fits then Right(()) else Left(HeaderFault.ExcessBlobGasMismatch(stated, required))

  /** What a block's excess must be, derived from its parent.
    *
    * The parent's own excess plus what it spent, less the target, floored at
    * zero. `ethereum/execution-specs` @ `0cc100eb1`
    * `src/ethereum/forks/cancun/vm/gas.py:384-413` and `ethereum/EIPs` @
    * `d2a64c2d4` `EIPS/eip-4844.md:155-159` state the identical three lines.
    *
    * ==Arbitrary precision, because the sum can leave the range the fields have==
    *
    * Both addends are 64-bit and their sum need not be, so the arithmetic runs
    * wider than the fields and the caller decides what to do when the answer
    * does not fit back into one. A sum taken in the field's own width would wrap
    * and produce a small excess for a parent claiming an enormous one, which is
    * a wrong answer rather than a refusal.
    *
    * ==It reads the parent and never the block==
    *
    * For [[baseFeeFor]]'s reason: what a block MUST state cannot depend on what
    * it DOES state.
    */
  private def excessBlobGasFor(parent: Resolved, schedule: BlobSchedule): BigInt =
    val spent = parent.header.excessBlobGas.map(_.toBigInt).getOrElse(BigInt(0)) +
      parent.header.blobGasUsed.map(_.toBigInt).getOrElse(BigInt(0))
    val target = schedule.targetBlobs * BlobGasPerBlob
    if spent < target then BigInt(0) else spent - target

  /** A header states the root of the beacon block its parent was built against
    * exactly where its fork requires one.
    *
    * ==The same pair as [[checkWithdrawalsRoot]], and the ONLY check this field
    * ever gets==
    *
    * Presence and absence are both rules, as they are for the fee market, the
    * withdrawals commitment and the blob-gas link. What differs is what happens
    * afterwards. Every other commitment a header states is a function of the
    * block, so a caller holding a body re-settles it and this layer's check is
    * an earlier and weaker one. **This value is handed in from the consensus
    * layer and derivable from nothing**, so no later caller can compare it
    * against anything, and a fork's requirement that the field be present or
    * absent is the whole of what any layer here can enforce about it.
    *
    * `org.fukuii.chainspec.HeaderRules.carriesParentBeaconBlockRoot` says the
    * same thing from the rule's side; this is the reader that makes the member
    * admissible.
    *
    * ==Two sources, enforcing it two different ways==
    *
    * `ethereum/go-ethereum` @ `02872e9ef` `consensus/beacon/consensus.go:263-268`
    * checks the identical pair as a rule -- *"invalid parentBeaconRoot, have
    * %#x, expected nil"* below the fork and *"header is missing beaconRoot"* at
    * or above it.
    *
    * `ethereum/execution-specs` @ `0cc100eb1` enforces it STRUCTURALLY instead,
    * and the difference is worth stating because it is why a rule is needed
    * here at all: `parent_beacon_block_root` is a required field of
    * `forks/cancun/blocks.py`'s `Header` and absent from
    * `forks/shanghai/blocks.py`'s, so a header on the wrong side of the fork
    * does not decode rather than failing a check. `org.fukuii.types.BlockHeader`
    * carries one type across every fork with the field on an optional chained
    * tail, which is what a client running more than one network family needs and
    * is exactly what moves this from the decoder to here.
    *
    * ==The value is unread, and the reason differs from the withdrawals case==
    *
    * There the root is compared where the body is. Here there is nowhere: the
    * option is matched on and its contents are never looked at, which is the
    * type stating that this layer holds a commitment it cannot check.
    */
  private def checkParentBeaconBlockRoot(block: Resolved): Either[HeaderFault, Unit] =
    (block.rules.header.carriesParentBeaconBlockRoot, block.header.parentBeaconBlockRoot) match
      case (false, None)         => Right(())
      case (false, Some(stated)) => Left(HeaderFault.ParentBeaconBlockRootUnexpected(stated))
      case (true, None)          => Left(HeaderFault.ParentBeaconBlockRootMissing)
      case (true, Some(_))       => Right(())

  /** A header commits to its block's execution-layer requests exactly where its
    * fork defines the commitment.
    *
    * ==Presence and absence, and deliberately not the value==
    *
    * [[checkWithdrawalsRoot]]'s split exactly, and for the same reason: the
    * commitment is a function of the block, so what it must BE needs an
    * executed body and belongs to [[BlockValidator]]. A header alone settles
    * only whether the field should be there.
    *
    * ==This is the strictest reading available, and the clients are three ways
    * apart on it==
    *
    * The executable specification settles it structurally -- Prague's header
    * dataclass requires the field -- and compares the value in
    * `state_transition` rather than in `validate_header`
    * (`ethereum/execution-specs` @ `0cc100eb1`,
    * `src/ethereum/forks/prague/fork.py:263`), so it states no presence rule
    * for a header read alone.
    *
    *   - `NethermindEth/nethermind` @ `3a98e0818` checks **both** directions
    *     (`Nethermind.Consensus/Validators/HeaderValidator.cs:105-122`).
    *   - `besu-eth/besu` @ `b330564a94` checks **presence only**, in a detached
    *     header rule (`RequestsHashPresentValidationRule.java`, installed at
    *     `MainnetBlockHeaderValidator.java:150`).
    *   - `ethereum/go-ethereum` @ `02872e9ef` checks **neither** -- it compares
    *     the value only when the field is present
    *     (`core/block_validator.go:177-181`).
    *
    * **This build checks both**, following nethermind and its own
    * [[checkWithdrawalsRoot]] precedent. The absence half is where it diverges
    * from two of the three: a header carrying a commitment its fork does not
    * define is one no producer should emit, and accepting it would let a
    * pre-Prague header state a value nothing on that chain can derive.
    *
    * **Revisit if** a published case states such a header valid, which would
    * settle it against this build -- that is the reversing trigger, and no case
    * read for this states one.
    */
  private def checkRequestsHash(block: Resolved): Either[HeaderFault, Unit] =
    (block.rules.header.carriesRequestsHash, block.header.requestsHash) match
      case (false, None)         => Right(())
      case (false, Some(stated)) => Left(HeaderFault.RequestsHashUnexpected(stated))
      case (true, None)          => Left(HeaderFault.RequestsHashMissing)
      case (true, Some(_))       => Right(())

  /** The header fields a fork holds at a constant, against those constants.
    *
    * ==Three values, and each is the specification's rather than this build's==
    *
    * A zero difficulty, an eight-byte zero nonce, and the hash of the empty list
    * -- `ethereum/EIPs` @ `dbfa6bee8` (2026-08-26), `EIPS/eip-3675.md:83-87`,
    * which tabulates five rows where three are checkable on a header alone. The
    * fourth, `mixHash`, is fixed to zero by that table and then unfixed by
    * EIP-4399, which the same document points at two lines further on; see
    * [[org.fukuii.chainspec.proposals.eip.Eip4399]] for why the later document
    * governs. The fifth is the ommers list itself, which is a body and not a
    * header.
    *
    * ==Nothing here reads a parent, and that is what makes it a constant rule==
    *
    * Every other check in this object compares a header against its parent.
    * These compare a header against a figure the fork fixes, so the parent is
    * unread -- which is why this is the one rule in this object that would give
    * the same answer over a header with no parent at all.
    */
  private def checkConstants(block: Resolved): Either[HeaderFault, Unit] =
    block.rules.header.constants match
      case HeaderConstants.Unconstrained => Right(())
      case HeaderConstants.Eip3675       =>
        val header = block.header
        if header.difficulty != UInt256.Zero then Left(HeaderFault.DifficultyNotFixed(header.difficulty))
        else if header.ommersHash != HeaderValidator.EmptyOmmersHash then
          Left(HeaderFault.OmmersNotEmpty(header.ommersHash))
        else
          header.seal match
            case Seal.MixHashAndNonce(_, nonce) =>
              // The mixed hash is deliberately unread. EIP-3675 fixes it to zero
              // and EIP-4399 fills it with the beacon chain's randomness, so a
              // fork carrying both -- which is every fork that has either --
              // constrains this slot not at all.
              if nonce != BlockNonce.Zero then Left(HeaderFault.NonceNotFixed(nonce)) else Right(())
            case Seal.AuthorityRound(_, _) => Left(HeaderFault.SealShapeUnexpected)

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

  /** A block allows itself no more gas than [[MaxGasLimit]].
    *
    * ==The specification is divided on it==
    *
    * `ethereum/execution-specs` @ `0cc100eb1` names the rule in its test
    * vocabulary -- `GASLIMIT_TOO_BIG`, *"Block header's gas limit >
    * 0x7fffffffffffffff."*
    * (`packages/testing/src/execution_testing/exceptions/exceptions/block.py:45-46`)
    * -- and its state transition does not check it: `check_gas_limit` bounds a
    * limit only against its parent's and a floor
    * (`src/ethereum/forks/cancun/fork.py:820-857`). Its blockchain-test loader
    * expects the one published case above the maximum to fail
    * (`tests/json_loader/helpers/exceptional_test_patterns.py:86`), in a list
    * headed *"These are tests that are considered to be incorrect, Please provide
    * an explanation when adding entries"* (`:75-76`). The heading is the list's,
    * not a reading of this case: the same list holds `bcMultiChainTest`,
    * `bcTotalDifficultyTest`, `bcForkStressTest` and `bcForgedTest`, this entry
    * carries none of the explanation the heading asks for, and it was first
    * added beside the comment *"Unclear where this failed requirement comes
    * from"* (commit `52aedfe60`).
    *
    * ==Who checks it, and how==
    *
    * **By value**, three production lineages and the tool that filled the case,
    * one lineage's value read at the last ref whose own tree defined it:
    *
    *   - `ethereum/go-ethereum` @ `02872e9ef`, in each of its three engines
    *     (`consensus/beacon/consensus.go:232`, `consensus/ethash/consensus.go:243`,
    *     `consensus/clique/clique.go:291`); from the same lineage
    *     `ethereumclassic/core-geth` @ `4185df450`, `lyra2` among its engines
    *     (`consensus/lyra2/consensus.go:234-237`), and `erigontech/erigon` @
    *     `ab8e9fde7`, in the header checks its ethash, authority-round and merge
    *     rules share (`execution/protocol/rules/ethash/rules.go:219`, reached from
    *     `rules/aura/aura.go:387`, and `rules/merge/merge.go:339`);
    *   - `NethermindEth/nethermind` @ `3a98e0818`, in its header validator
    *     (`Nethermind.Consensus/Validators/HeaderValidator.cs:174-184`);
    *   - `paradigmxyz/reth` @ `e63ec720ac`, against a named constant,
    *     `MAXIMUM_GAS_LIMIT_BLOCK`, imported from the `reth-primitives-traits`
    *     0.7.0 crate that tree takes from outside itself (`Cargo.toml:397`,
    *     `crates/consensus/common/src/validation.rs:8-10,30-32`); that crate was
    *     not read. The same check at `e91a900dd7` reads it from its own tree, as
    *     `2u64.pow(63) - 1` (`crates/primitives-traits/src/constants/mod.rs:15`);
    *     the next commit, `6183361f83`, moved the constants out;
    *   - `ethereum/retesteth` @ `949f9b21`
    *     (`retesteth/session/ToolBackend/Verification.cpp:315-316`).
    *
    * **Not by value**, one lineage that still refuses the published block.
    * `besu-eth/besu` @ `b330564a94` states the figure
    * (`MainnetBlockHeaderValidator.java:47`) and cannot reach it: a header's
    * limit is a signed `long`, the reader assembles a limit of `2^63` into
    * `Long.MIN_VALUE` (`ethereum/rlp/.../BytesValueRLPInput.java:322-337`), and
    * no comparison against the figure can then fire. Its merge rules refuse such
    * a block at the gas-used rule instead (`MergeValidationRulesetFactory.java:63`,
    * `GasUsageValidationRule.java:35`). `besu-eth/besu-etc` @ `eb4248c997` has the
    * same figure, rule and reader (`MainnetBlockHeaderValidator.java:49,106`).
    *
    * **Not at all:** `lambdaclass/ethrex` @ `3954106507`
    * (`crates/common/types/block.rs:509-515`).
    *
    * ==Checked here, so this build differs from ethrex and from the
    * specification's state transition rather than from the lineages above==
    *
    * A limit above the maximum is observable wherever a chain can reach one: a
    * chain launched with its limit at the maximum admits a child above it under
    * the parent's bound. `ethereum/legacytests` @ `1f581b8cc` states exactly that
    * block, `GasLimitHigherThan2p63m1`: a limit of `2^63` over a parent at
    * `2^63 - 1`, refused as `InvalidGasLimit`.
    *
    * **Revisit if** the specification's state transition adopts the maximum,
    * which would settle it, or rejects it, or a production client family drops
    * it -- either of the last two argues for reversing this.
    *
    * ==Shared, because no source read places it on a fork or an engine==
    *
    * None of the lineages that check it gates it on a fork, and each that carries
    * more than one mechanism checks it in every one read. So it sits with the
    * rules every mechanism shares rather than on the engine seam.
    *
    * ==Its own rule rather than a part of the bound==
    *
    * [[checkGasLimit]] reads the parent and this reads the header alone, and a
    * block can break both. Kept apart, [[faults]] can report either.
    */
  private def checkGasLimitMaximum(header: BlockHeader): Either[HeaderFault, Unit] =
    val stated = header.gasLimit.toBigInt
    if stated > MaxGasLimit then Left(HeaderFault.GasLimitAboveMaximum(stated, MaxGasLimit)) else Right(())

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
