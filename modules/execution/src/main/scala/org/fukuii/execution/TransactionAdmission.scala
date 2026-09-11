package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.crypto.Secp256k1
import org.fukuii.evm.{BlobGas, GasSchedule, WorldState}
import org.fukuii.types.{AccessTuple, Sender, SignatureScheme, Transaction, TransactionType}

/** Why a fork refuses a transaction.
  *
  * Typed rather than a message, because a refusal is compared: the published
  * corpora state which rule a transaction must be refused by, and a case
  * expecting one refusal must not be satisfied by another. A refused
  * transaction leaves the world exactly as it was whichever branch refused it,
  * so the state root -- the strongest check there is on such a case -- cannot
  * tell two reasons apart, and only the reason can.
  *
  * The names are this project's for the rules rather than the specification's
  * for its exceptions, because a refusal here is a network's answer and not one
  * fork's: a format is refused for not being admitted, which is true of a
  * network that never carried it as much as of one whose fork predates it.
  */
enum Refusal:

  /** This network does not carry transactions of this format. */
  case TypeNotAdmitted

  /** The limit cannot pay the charge every transaction pays before it runs. */
  case IntrinsicGasTooLow

  /** The transaction deploys, and the code it offers to initialize with is
    * longer than these rules admit.
    *
    * A rule of its own rather than a shortfall of the charge above, though the
    * document points at the resemblance -- *"Note that this is similar to
    * transactions considered invalid for not meeting the intrinsic gas cost
    * requirement"* (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-3860.md`, Final,
    * rule 1). **Similar is not the same**: a transaction can carry a limit
    * covering every charge and still be refused here, so the two cannot be one
    * branch, and the corpus states which of them refused.
    */
  case InitcodeTooLarge

  /** The transaction count is at the ceiling, so no successor could be signed
    * for.
    */
  case NonceIsMax

  /** The transaction asks for more gas than the block has left to give. */
  case GasAllowanceExceeded

  /** The transaction count is not the one the sender holds. */
  case NonceMismatch

  /** The sender cannot cover the whole fee it offers plus the value it sends. */
  case InsufficientAccountFunds

  /** The sender holds code, so it is not an externally owned account. */
  case SenderNotEoa

  /** The signature names a chain identifier that is not this network's. */
  case WrongChainId

  /** The signature names no account this fork accepts. */
  case InvalidSignature

  /** The most the transaction will pay per unit of gas is less than the charge
    * the block sets.
    *
    * ==One reason, where the specification raises two exceptions==
    *
    * `ethereum/execution-specs` @ `20f7f6271a` `forks/london/fork.py` refuses a
    * fee-market transaction whose cap is under the charge at `:503` and a
    * fixed-price one at `:517`, by two different exceptions. **They are one
    * rule** -- what the transaction is willing to pay cannot cover what the
    * block charges -- and this enum names rules rather than exceptions, which
    * its own opening states. What differs between the two is only which field
    * states the ceiling, and [[FeeOffer.cap]] is where that difference already
    * lives.
    *
    * **It reaches formats that predate the fee market**, which is the half a
    * reader expects least: a fixed-price transaction at a fork with a market is
    * refused by this rule exactly as a capped one is.
    */
  case FeeCapBelowBaseFee

  /** The tip offered exceeds the most the transaction will pay in total.
    *
    * Reachable only by a format stating a cap and a tip separately, since no
    * other format can express the pair.
    */
  case PriorityFeeAboveFeeCap

  /** A format that may not deploy offers no recipient.
    *
    * ==Unreachable from any DECODED transaction, in this build and in the
    * specification alike==
    *
    * `org.fukuii.types.Transaction.Blob` types its recipient as an address
    * rather than an address-or-empty, and `ethereum/execution-specs` @
    * `0cc100eb1` types it identically -- `to: Address` on
    * `src/ethereum/forks/cancun/transactions.py:305`. So neither tree can
    * decode a blob transaction that deploys, and neither can reach this rule
    * from one. The specification keeps the guard anyway, at
    * `forks/cancun/fork.py:469-470`, because its `check_transaction` is generic
    * over a union whose earlier members DO deploy; this exists for the same
    * reason, because [[OfferedTransaction.to]] is an option for the same
    * reason.
    *
    * ==So it has no caller today, and that is stated rather than implied==
    *
    * Both builders of an offer in this repository read a decoded transaction,
    * so neither can produce the state. **The published corpus does not reach it
    * either**: the one state fixture that names an empty recipient beside a
    * commitment list publishes signed bytes no conformant decoder reads, so
    * this build reports that case undecodable and the refusal below is not what
    * answers it.
    *
    * It is kept because the record ADMITS the state and the next caller of this
    * layer is one that builds an offer from something other than a decoded
    * transaction. Without it such an offer is not refused -- it is settled as a
    * DEPLOYMENT, which is a wrong answer rather than a wrong reason.
    *
    * Named for the rule rather than for the blob format, because the document
    * after this one states the same rule for its own format and the
    * specification raises the same exception for both.
    */
  case FormatMayNotDeploy

  /** A format that carries blobs carries none.
    *
    * ==A rule and not an encoding refusal, which is why it is here==
    *
    * An empty list encodes and decodes unambiguously, so the transaction is
    * well-formed and the fork is what refuses it:
    * *"there must be at least one blob"* (`ethereum/EIPs` @ `d2a64c2d4`
    * (2026-09-11), `EIPS/eip-4844.md:271`, Final).
    * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11) raises
    * `NoBlobDataError` from `src/ethereum/forks/cancun/fork.py:471-472`, inside
    * transaction checking rather than inside decoding.
    *
    * `org.fukuii.types.Transaction` records the same split for the
    * authorization list of the format after this one, and the corpora agree by
    * giving each its own expected exception.
    */
  case BlobListEmpty

  /** A commitment the transaction carries names a scheme these rules do not
    * know.
    *
    * `org.fukuii.evm.BlobGas.VersionedHashVersion` is the byte and carries the
    * evidence. **Every commitment is read, not the first** -- the published
    * corpus states a case whose second commitment is the malformed one and its
    * first is well-formed, so a check that stopped at the first would admit a
    * transaction the network refuses.
    */
  case BlobHashVersionUnknown

  /** The most the transaction will pay per unit of blob gas is less than the
    * charge the block sets for it.
    *
    * The blob half of [[FeeCapBelowBaseFee]], and a separate rule rather than
    * the same one because the two read different fields against different
    * charges -- a transaction can offer enough for its gas and not enough for
    * its blobs, and the corpus states which refused it.
    */
  case BlobFeeCapBelowCharge

  /** The transaction asks for more blob gas than the block has left to give.
    *
    * The blob half of [[GasAllowanceExceeded]], and against the REMAINDER for
    * the same reason: `ethereum/execution-specs` @ `0cc100eb1`
    * `src/ethereum/forks/cancun/fork.py:432` opens
    * `blob_gas_available = MAX_BLOB_GAS_PER_BLOCK - block_output.blob_gas_used`
    * exactly as it opens the gas figure on the line above.
    *
    * **One surveyed client bounds the TRANSACTION instead**, which is a weaker
    * rule that agrees on a block carrying one transaction and disagrees on a
    * block carrying two: `besu-eth/besu` @ `b330564a9`
    * `MainnetTransactionValidator.java:228-234` compares `txTotalBlobGas`
    * against the fork's maximum and reports `TOTAL_BLOB_GAS_TOO_HIGH`. The
    * specification is followed here.
    */
  case BlobGasAllowanceExceeded

/** A transaction offered for admission: the values a fork's rules are read
  * against, with the sender already settled.
  *
  * ==Its own record, and not [[AdmittedTransaction]]==
  *
  * The two carry almost the same fields and answer opposite questions. This is
  * what a caller presents; that is what admission produced and settlement
  * spends, and its name states a precondition this type is the discharge of.
  * Folding them into one would leave the surviving name wrong on one side of
  * the seam, and it is the seam's whole point that the two are told apart.
  *
  * The one field this carries that settlement does not is [[transactionType]],
  * because whether a network carries a format at all is decided here and never
  * again.
  *
  * ==The quantities are arbitrary precision, for the reason settlement's are==
  *
  * A published state test states a nonce, a limit, a price and a value that a
  * fixed-width type cannot always hold, and overflow at each of them is a thing
  * the corpus tests. Narrowing here would turn a case a fork must refuse into a
  * case this build cannot read.
  *
  * @param sender
  *   the account the signature named, which pays the fee and whose transaction
  *   count must match. [[TransactionAdmission.senderOf]] is what settles it
  *   where a signature is available.
  * @param to
  *   the recipient, absent when the transaction deploys.
  * @param accessList
  *   the accounts and slots the transaction declares ahead of running, empty
  *   for every format that carries no such declaration.
  *
  *   **It is here because the intrinsic charge is priced from it**, and this
  *   record's whole purpose is to carry what the branch comparing that charge
  *   against the limit reads. Without it a transaction whose limit covers only
  *   the base and its data is ADMITTED where a fork pricing the declaration
  *   must refuse it -- which is not a refusal difference but a settled
  *   transaction the network does not carry.
  *
  *   Carried as the sequence the transaction stated, never narrowed to a set:
  *   [[IntrinsicGas]] charges duplicates and the warm seed built from the same
  *   field does not, and only the sequence supports both.
  */
final case class OfferedTransaction(
    transactionType: TransactionType,
    sender: Address,
    nonce: BigInt,
    fee: FeeOffer,
    gasLimit: BigInt,
    to: Option[Address],
    value: BigInt,
    data: Bytes,
    accessList: Seq[AccessTuple],
    blobs: Option[BlobOffer]
)

/** What a blob-carrying transaction states about its blobs.
  *
  * ==A record, because the two fields are only ever present together==
  *
  * One format states both and every other format states neither, so two
  * independent options would admit two states no transaction can reach -- a
  * ceiling with no commitments, and commitments with no ceiling. They would
  * also sit adjacent in a parameter list at the same type as each other and as
  * the fee-market pair beside them, where transposing any two compiles and
  * settles wrongly. `org.fukuii.evm.EvmRules` is refused a member nothing reads
  * on the same principle: a shape that can express a configuration no fork has
  * is a shape somebody eventually writes.
  *
  * ==Absent and empty are different answers, and the difference is a refusal==
  *
  * `None` is a transaction of a format that carries no blobs at all;
  * `Some` with an empty sequence is a blob transaction carrying none, which
  * [[Refusal.BlobListEmpty]] refuses. Collapsing the two would make that rule
  * unreachable and would refuse every ordinary transaction instead --
  * `org.fukuii.execution.BlockOutput.withdrawalsRoot` keeps the same
  * distinction for the same reason.
  *
  * @param maxFeePerBlobGas
  *   the most the transaction will pay per unit of blob gas. It is read by two
  *   rules that do different things with it: the charge it is compared against
  *   is the block's, and the figure added to what the sender must hold is this
  *   one -- **never the block's charge**, which is the same cap-against-price
  *   split [[FeeOffer]] records at length for gas.
  * @param versionedHashes
  *   the commitments, in the order the transaction states them. Carried as the
  *   stated sequence rather than narrowed to a set: the count is what the blob
  *   gas is priced from, and duplicates are counted.
  */
final case class BlobOffer(maxFeePerBlobGas: BigInt, versionedHashes: Seq[Hash])

/** What a block sets for blob gas: what it charges per unit, and how much of it
  * the block has left to give.
  *
  * ==One record rather than two parameters, because the pair is
  * transposable==
  *
  * Both are arbitrary-precision quantities and both would sit beside the two
  * the fee market already contributes, so four same-typed values would reach
  * [[TransactionAdmission.admit]] positionally with nothing to tell a
  * transposition from a correct call. Each member here is named at its one
  * construction site instead.
  *
  * ==Neither member is a fork constant, which is why this is not on
  * [[AdmissionRules]]==
  *
  * The charge is derived from the excess the block's own header states and the
  * fraction the fork resolves; the remainder is the fork's maximum less what
  * the transactions before this one already spent. Both are therefore answers
  * about one block at one position in it, which is what `gasAvailable` and the
  * base fee already are -- and both are computed a layer up, for the reason
  * `maxInitcodeSize` is passed in rather than duplicated here.
  *
  * @param charge
  *   what the block charges per unit of blob gas.
  *   `org.fukuii.evm.BlobGasPrice.at` is the derivation and carries its
  *   evidence.
  * @param available
  *   what the block has left to spend on blobs, which is its fork's maximum
  *   less what the transactions before this one carried.
  */
final case class BlobGasTerms(charge: BigInt, available: BigInt)

/** What a transaction offers to pay per unit of gas.
  *
  * ==A sum, because two of the quantities are genuinely different numbers==
  *
  * A format stating a cap and a tip separately is offering two figures that a
  * settled transaction resolves into one, and **the resolution needs the
  * block's charge, which the transaction does not carry.** So the offer cannot
  * be reduced to a single price where it is made; it is reduced at admission,
  * where a base fee is finally available.
  *
  * ==Why one price here would be admitting transactions no conformant node
  * admits==
  *
  * The two figures are read by two different rules. The balance check is made
  * against the CAP -- `ethereum/execution-specs` @ `20f7f6271a`
  * `forks/london/fork.py:513` computes `max_gas_fee` from `max_fee_per_gas` --
  * and the up-front charge against the EFFECTIVE price, at `:778`. Folding them
  * into one field makes the balance check read whichever survived, and where
  * that is the effective price the check passes on a balance every conformant
  * node refuses.
  *
  * **The failure is a NARROWER refusal set, which is why a state fixture would
  * not catch it**: the transaction is admitted rather than refused, so a case
  * asserting a successful transaction still agrees.
  *
  * ==A fixed price is not a cap of itself with a zero tip==
  *
  * Both cases could be expressed as [[Capped]], and doing so would lose the one
  * thing this type is for: a fixed-price transaction pays its stated price
  * whatever the block charges, and a capped one pays what the block charges
  * plus a tip. They coincide only when the two arms are computed, never in what
  * the transaction stated -- and it is what was stated that a refusal is
  * compared against.
  */
enum FeeOffer:

  /** A format stating one price, which it pays whatever the block charges. */
  case Fixed(gasPrice: BigInt)

  /** A format stating the most it will pay in total and the most it will pay
    * above the block's charge.
    */
  case Capped(maxFeePerGas: BigInt, maxPriorityFeePerGas: BigInt)

  /** The most this offer will pay per unit of gas.
    *
    * What the balance check is made against, and what the block's charge is
    * compared to. Both formats state it; only the field differs.
    */
  def cap: BigInt = this match
    case Fixed(gasPrice)   => gasPrice
    case Capped(maxFee, _) => maxFee

  /** What this offer actually pays per unit of gas, given the block's charge.
    *
    * ==Stated as the specification states it, not as the algebraically equal
    * form==
    *
    * `ethereum/execution-specs` @ `20f7f6271a` `forks/london/fork.py:508-512`
    * takes the tip as `min(maxPriorityFeePerGas, maxFeePerGas - baseFee)` and
    * adds the charge back. **`min(maxFee, baseFee + maxPriorityFee)` is the same
    * number** and is the form the proposal's abstract suggests. This follows the
    * executable specification so the two read against each other directly.
    *
    * **Not for an overflow reason.** Both forms are computed in arbitrary
    * precision on both sides -- `BigInt` here, and the specification's own
    * unbounded integer type there -- so neither can form a value it cannot
    * hold, and the intermediate that exceeds the cap in one ordering is
    * harmless in both.
    *
    * Requires the caller to have refused an offer whose cap is under the
    * charge, which [[Refusal.FeeCapBelowBaseFee]] is; without that the
    * subtraction is negative and the tip arm is meaningless.
    */
  def effective(baseFee: BigInt): BigInt = this match
    case Fixed(gasPrice)             => gasPrice
    case Capped(maxFee, maxPriority) => maxPriority.min(maxFee - baseFee) + baseFee

/** Whether a transaction may run at all, and why not when it may not. */
enum Admission:

  /** @param transaction
    *   the values settling it spends, which is the whole of what settlement
    *   needs and is produced here so that no caller assembles its own.
    *
    *   **The intrinsic charge is among them rather than beside them.** Reported
    *   as a second member it is a figure every caller may drop on the way to
    *   settlement -- and one settlement would then have to work out again, from
    *   a schedule the caller chose, which is the same number acquiring a second
    *   definition. Carried on the record it cannot be separated from the
    *   transaction it was computed for.
    */
  case Admitted(transaction: AdmittedTransaction)

  case Refused(reason: Refusal)

/** What makes a transaction acceptable before any of it runs.
  *
  * ==Two entry points, because a sender is not always recovered here==
  *
  * [[senderOf]] reads the signature and [[admit]] reads the state, and they are
  * separate for the reason the field separates them: `ethereum/go-ethereum` @
  * `6bb0588ad` recovers a sender through a `Signer` bound to the network and
  * checks the state in `preCheck` on the flattened message, and
  * `ethereum/execution-specs` @ `ccaaaba58` splits the same work between
  * `validate_transaction` and `check_transaction`. A caller holding a signature
  * runs both in that order; a caller that already knows the sender runs the
  * second alone.
  *
  * ==Each entry point runs the specification's order; the two composed do not==
  *
  * A transaction can break two rules at once, and which refusal a client
  * reports is what the corpus states, so the order is load-bearing within each
  * of them. [[admit]] takes its rules from `ethereum/execution-specs` @
  * `ccaaaba58` in that document's own order: `frontier/transactions.py`'s
  * `validate_transaction` supplies the intrinsic charge and the nonce ceiling,
  * and `frontier/fork.py`'s `check_transaction` follows with
  * `GasUsedExceedsLimitError`, `NonceMismatchError`,
  * `InsufficientBalanceError` and `InvalidSenderError`. [[senderOf]] compares
  * the chain identifier before it recovers, as `spurious_dragon/fork.py` does.
  *
  * **Composed, they are not.** That document's `process_transaction` calls
  * `validate_transaction` first and reaches the chain identifier only inside
  * `check_transaction`, after the intrinsic charge, the nonce ceiling and the
  * gas allowance have all been read; a caller here runs the whole of
  * [[senderOf]] before any of them. So a transaction that both underpays its
  * intrinsic charge and names another network is
  * `InsufficientTransactionGasError` there and `WrongChainId` here. The
  * specification's order is reachable only by a caller that can offer a
  * transaction before its sender is settled, which is a property of what
  * [[OfferedTransaction]] requires rather than of the order below.
  *
  * **What differs is the reason, never a state root.** A refusal leaves the
  * world exactly as it was whichever rule produced it, and a block carrying
  * such a transaction is one this network does not accept either way -- so the
  * two orders condemn the same blocks and reach the same state, and only a
  * comparison of reasons can see the difference at all.
  *
  * **Nothing published at the forks this build carries reaches it**, because
  * both refusals have to be broken at once and no case on disk names a chain:
  * `TransactionAdmissionSpec` holds that measurement, taken by decoding every
  * `txbytes` in the generated state corpora rather than by matching their text.
  *
  * Every branch below reads a member of [[AdmissionRules]] or a rule that
  * document states applies to every fork, which is what keeps the policy an
  * operator may tune out of this object; that boundary is stated once, on the
  * record.
  */
object TransactionAdmission:

  /** A transaction count at or above this cannot be signed for, applied to
    * every fork.
    *
    * `ethereum/execution-specs` @ `ccaaaba58` states it as `if U256(tx.nonce)
    * >= U256(U64.MAX_VALUE)` in every fork's `validate_transaction`, and its
    * commentary records that EIP-2681 is applied retroactively rather than from
    * an activation -- which is why this is a constant here and not a member of
    * [[AdmissionRules]].
    */
  val NonceLimit: BigInt = (BigInt(1) << 64) - 1

  /** Whether this network carries transactions of this format at all.
    *
    * Visible, and read by both entry points, because it is the FIRST question
    * asked of a transaction and the two must not answer it separately. A client
    * meeting a transaction on the wire refuses it by format before spending
    * anything on its signature, so [[senderOf]] asks this before it recovers
    * and [[admit]] asks it before it reads state -- one predicate reached from
    * two places rather than one rule written twice.
    */
  def admitsFormat(transactionType: TransactionType, rules: AdmissionRules): Boolean =
    rules.admittedTypes.contains(transactionType)

  /** The account this network lets `transaction` run as.
    *
    * ==The chain identifier is compared HERE, and that is the point of it==
    *
    * `org.fukuii.types.Sender.recover` recovers the identifier out of a legacy
    * `v` in order to rebuild the preimage and deliberately compares it to
    * nothing, because it holds no network. **Returning an address there is not
    * a statement that the signature was made for this chain**, and admitting a
    * transaction signed for another one splits the chain: it would be settled
    * here and rejected by every node that made the comparison.
    *
    * The rule is `ethereum/execution-specs` @ `ccaaaba58`,
    * `forks/spurious_dragon/fork.py`: `if tx_chain_id is not None and
    * tx_chain_id != block_env.chain_id`, over a `chain_id` that answers `None`
    * for a `v` of 27 or 28. `ethereumclassic/core-geth` @ `4185df450` reaches
    * the same shape from the other side in `core/types/transaction_signing.go`,
    * where `EIP155Signer.Sender` hands an unprotected transaction to the
    * earlier signer untouched and otherwise refuses with `ErrInvalidChainId`.
    *
    * **An unprotected signature carries no identifier and is valid on every
    * network by construction**, so it is not a case this comparison decides and
    * it is skipped rather than defaulted. No member of [[AdmissionRules]]
    * governs whether such a signature is admitted, because no fork refuses one:
    * EIP-155 adds a scheme beside the earlier one rather than replacing it.
    *
    * ==Whether an identifier may be named at all is asked BEFORE which one it
    * names==
    *
    * The two rules answer differently and the order between them is observable,
    * because a signature can break both at once -- naming another network's
    * chain at rules that admit no identifier from anyone. Below EIP-155 the
    * refusal is the signature's and not the chain's, which is the answer both
    * the specification and the clients give:
    * `forks/frontier/transactions.py` has no `chain_id` function to reach, so
    * `recover_sender` refuses on `v` alone, and `ethereumclassic/core-geth` @
    * `4185df450` cannot report `ErrInvalidChainId` at all below the transition
    * because `MakeSigner` never selects the signer that raises it.
    * [[AdmissionRules.signatureMayCarryChainId]] is therefore read ahead of the
    * comparison, and reversing the two would report a chain mismatch for a
    * transaction the fork refuses without ever reading which chain it named.
    *
    * ==EIP-2's bound is applied before recovery, not inside it==
    *
    * An `s` above half the curve order and its mirror image recover the SAME
    * account under two different transaction hashes, so the duplicate is not
    * something the curve can suppress and only a fork can refuse. The curve
    * layer states that it bounds `r` and `s` to the field alone and leaves this
    * to whoever holds the fork's rules; this is that place.
    *
    * @param chainId
    *   this network's registered identifier.
    */
  def senderOf(transaction: Transaction, chainId: UInt64, rules: AdmissionRules): Either[Refusal, Address] =
    if !admitsFormat(transaction.transactionType, rules) then Left(Refusal.TypeNotAdmitted)
    else if !rules.signatureMayCarryChainId && namesChainInSignature(transaction) then Left(Refusal.InvalidSignature)
    else if signedForAnotherChain(transaction, chainId) then Left(Refusal.WrongChainId)
    else if rules.signatureSMustBeLow && Sender.signatureOf(transaction).exists(_.s > Secp256k1.halfCurveOrder)
    then Left(Refusal.InvalidSignature)
    else Sender.recover(transaction).left.map(_ => Refusal.InvalidSignature)

  /** Whether a block at these rules would carry `offered`.
    *
    * ==The four reads are lazy, and the laziness is not an optimization==
    *
    * Each of them is deferred to the branch that wants it, so a transaction
    * refused for its format costs no state lookup and no arithmetic.
    * `maximumFee` in particular is an unbounded multiplication of two
    * magnitudes a caller supplies, performed after the branch that bounds one
    * of them rather than before it -- and admission is exactly where that
    * ordering matters, because admission is what faces a transaction that
    * arrived from somewhere else.
    *
    * @param baseFeePerGas
    *   what the BLOCK charges, absent where the fork runs no fee market.
    *
    *   **The caller owes the pair being consistent**, and nothing here can check
    *   it: these rules do not carry a fee market, so admission cannot tell a
    *   fork with no market from a caller that forgot to pass its charge.
    *   `org.fukuii.consensus.HeaderValidator` is what establishes it -- it
    *   refuses a header stating no charge under a fork with a market, and one
    *   stating a charge under a fork without.
    * @param gasAvailable
    *   what the block has left to give, which is its limit less the gas already
    *   used by the transactions before this one. It is the remainder rather
    *   than the limit because the specification states it that way -- `forks`'
    *   `check_transaction` opens `gas_available = block_env.block_gas_limit -
    *   block_output.block_gas_used` -- and a caller settling one transaction
    *   against an otherwise empty block passes the limit itself.
    * @param maxInitcodeSize
    *   the longest code a deploying transaction may offer, absent where the
    *   rules bound none. **A value the MACHINE's rules hold, passed in rather
    *   than duplicated here**, for the reason the schedule beside it is: one
    *   number bounds both a create transaction's data and a create operation's
    *   operand, and a copy on this facet would be a second definition for a fork
    *   to keep in step.
    *
    *   `besu-eth/besu` @ `fdf1247c6d` threads it the same direction and from the
    *   same place, constructing its transaction validator with
    *   `evm.getMaxInitcodeSize()` at each fork definition that has one
    *   (`MainnetProtocolSpecs.java:776`), against a
    *   `private final int maxInitcodeSize` the validator then compares
    *   (`MainnetTransactionValidator.java:63,131`).
    */
  def admit(
      offered: OfferedTransaction,
      world: WorldState,
      gasAvailable: BigInt,
      baseFeePerGas: Option[BigInt],
      blobGas: Option[BlobGasTerms],
      rules: AdmissionRules,
      schedule: GasSchedule,
      maxInitcodeSize: Option[Int]
  ): Admission =
    lazy val intrinsic = IntrinsicGas.of(schedule, offered.data, offered.to.isEmpty, offered.accessList)
    lazy val counted = world.nonceOf(offered.sender).toBigInt
    lazy val held = world.balanceOf(offered.sender).toBigInt
    // The CAP, never the effective price. `FeeOffer` states why at length: the
    // two are different numbers under a fee market, and this is the check the
    // specification makes against the higher of them.
    lazy val maximumFee = offered.gasLimit * offered.fee.cap
    // A block below any fee market charges nothing, and that is not the same
    // fact as a block charging zero -- the machine refuses to collapse the two
    // one layer down, where `org.fukuii.evm.Environment` argues that zero is
    // itself a legal charge so the substitution is unrecoverable. Here the two
    // branches happen to agree, because a cap is never below nothing and a
    // charge subtracted from a price is never subtracted at all. They are
    // written separately anyway, so that a later rule reading `charge` cannot
    // silently inherit a zero that means absence.
    val charge = baseFeePerGas
    val underCharge = charge.exists(offered.fee.cap < _)
    // Zero blob gas for every format that carries no blobs, and for a blob
    // transaction carrying an empty list -- which the branch below refuses
    // before this figure is spent on anything.
    lazy val blobGasWanted = offered.blobs.map(held => BlobGas.spentOn(held.versionedHashes)).getOrElse(BigInt(0))
    if !admitsFormat(offered.transactionType, rules) then Admission.Refused(Refusal.TypeNotAdmitted)
    else if intrinsic > offered.gasLimit then Admission.Refused(Refusal.IntrinsicGasTooLow)
    // IMMEDIATELY AFTER THE INTRINSIC CHARGE, which is where the specification
    // puts it: `ethereum/execution-specs` @ `20f7f6271a`
    // `forks/shanghai/transactions.py:339-341` raises
    // `InsufficientTransactionGasError` and then `InitCodeTooLargeError` on the
    // next line, above the nonce ceiling. A transaction can break both at once
    // -- oversized initcode is also data the limit may not cover -- so which is
    // reported is the order's to decide and the corpus states it.
    //
    // Two clients settle it on the same side. `ethereumclassic/core-geth` @
    // `4185df450` compares the intrinsic charge at
    // `core/state_transition.go:426` and refuses the length at `:449`, and
    // `ethereum/go-ethereum` @ `e9e35a42f` reaches
    // `vm.CheckMaxInitCodeSize` at `core/state_transition.go:636`, likewise
    // below its own intrinsic check. **Both put it on the settling path and not
    // in the transaction pool alone** -- core-geth carries a second copy in
    // `core/txpool/validation.go:79`, which is what a node relays by and would
    // leave the rule unenforced on a block it received.
    else if offersOversizedInitcode(offered, maxInitcodeSize) then Admission.Refused(Refusal.InitcodeTooLarge)
    else if tipExceedsCap(offered.fee) then Admission.Refused(Refusal.PriorityFeeAboveFeeCap)
    else if offered.nonce >= NonceLimit then Admission.Refused(Refusal.NonceIsMax)
    else if offered.gasLimit > gasAvailable then Admission.Refused(Refusal.GasAllowanceExceeded)
    // IMMEDIATELY AFTER THE GAS ALLOWANCE, where the specification puts it:
    // `ethereum/execution-specs` @ `0cc100eb1`
    // `src/ethereum/forks/cancun/fork.py:431-439` opens the two remainders on
    // consecutive lines and compares each on the line after the one that opened
    // it. A transaction can exceed both at once, so which is reported is the
    // order's to decide.
    else if blobGas.exists(blobGasWanted > _.available) then Admission.Refused(Refusal.BlobGasAllowanceExceeded)
    else if underCharge then Admission.Refused(Refusal.FeeCapBelowBaseFee)
    // BELOW the gas cap and ABOVE the nonce, which is the position the
    // specification gives the whole blob block: `fork.py:468-489` runs it after
    // the fee-market arm computes `max_gas_fee` and before
    // `sender_account.nonce` is read at all. The three rules inside it are in
    // that document's own order too -- an empty list, then the commitments'
    // versions, then the ceiling against the charge -- and a transaction can
    // break more than one at once.
    else if deploysWhereItMayNot(offered) then Admission.Refused(Refusal.FormatMayNotDeploy)
    else if blobsAreEmpty(offered) then Admission.Refused(Refusal.BlobListEmpty)
    else if carriesUnknownBlobVersion(offered) then Admission.Refused(Refusal.BlobHashVersionUnknown)
    else if blobFeeCapUnderCharge(offered, blobGas) then Admission.Refused(Refusal.BlobFeeCapBelowCharge)
    else if counted != offered.nonce then Admission.Refused(Refusal.NonceMismatch)
    else if held < maximumFee + blobMaximumFee(offered) + offered.value then
      Admission.Refused(Refusal.InsufficientAccountFunds)
    else if world.codeOf(offered.sender).nonEmpty then Admission.Refused(Refusal.SenderNotEoa)
    else
      Admission.Admitted(
        settling(offered, intrinsic, charge.getOrElse(BigInt(0)), blobGasWanted, blobGas.map(_.charge))
      )

  /** Whether a transaction of a format that may not deploy has no recipient.
    *
    * Keyed on the blob offer for the reason every rule in this block is: it is
    * the marker this record carries for the format, and reading the format's
    * tag instead would answer for a record whose own fields say otherwise.
    * [[Refusal.FormatMayNotDeploy]] carries the evidence and the reason a
    * decoded blob transaction can never reach it.
    */
  private def deploysWhereItMayNot(offered: OfferedTransaction): Boolean =
    offered.blobs.isDefined && offered.to.isEmpty

  /** Whether a transaction of a blob-carrying format carries no blobs.
    *
    * Reads the offer's presence and not the format's tag, so the rule cannot
    * fire on a transaction that has no such field to be empty --
    * [[BlobOffer]] states why the two are kept apart.
    */
  private def blobsAreEmpty(offered: OfferedTransaction): Boolean =
    offered.blobs.exists(_.versionedHashes.isEmpty)

  /** Whether any commitment the transaction carries names a scheme these rules
    * do not know.
    *
    * `exists` over the whole sequence rather than a test of the first, because
    * the published corpus states cases whose malformed commitment is the
    * second -- `invalid_blob_hash_versioning_single_tx` carries one such case
    * in each position.
    */
  private def carriesUnknownBlobVersion(offered: OfferedTransaction): Boolean =
    offered.blobs.exists(_.versionedHashes.exists(hash => !BlobGas.versionKnown(hash)))

  /** Whether the transaction's ceiling for blob gas is under the block's charge
    * for it.
    *
    * Both sides must be present for the comparison to mean anything, and a
    * caller holding one without the other is in the same broken state the gas
    * pair's own contract describes: a fork with a blob schedule states a charge
    * on every block, and a fork without one admits no format that could state a
    * ceiling.
    */
  private def blobFeeCapUnderCharge(offered: OfferedTransaction, blobGas: Option[BlobGasTerms]): Boolean =
    (offered.blobs, blobGas) match
      case (Some(held), Some(terms)) => held.maxFeePerBlobGas < terms.charge
      case _                         => false

  /** What the transaction's blobs add to the balance it must hold.
    *
    * ==The CEILING, never the block's charge==
    *
    * `ethereum/execution-specs` @ `0cc100eb1`
    * `src/ethereum/forks/cancun/fork.py:485-487` adds
    * `calculate_total_blob_gas(tx) * tx.max_fee_per_blob_gas` to `max_gas_fee`,
    * and `ethereum/go-ethereum` @ `02872e9ef`
    * `core/state_transition.go:456-459` builds its own balance check from
    * `st.msg.BlobGasFeeCap`. **What is actually taken from the sender is the
    * block's charge instead**, which go-ethereum computes separately on the
    * lines below and this build settles in
    * `org.fukuii.execution.TransactionProcessor`. Folding the two into one
    * figure admits a sender who cannot cover the ceiling it offered, exactly as
    * [[FeeOffer]] records for gas.
    */
  private def blobMaximumFee(offered: OfferedTransaction): BigInt =
    offered.blobs.map(held => BlobGas.spentOn(held.versionedHashes) * held.maxFeePerBlobGas).getOrElse(BigInt(0))

  /** Whether the transaction deploys more code than the rules admit.
    *
    * ==Both halves of the condition are load-bearing==
    *
    * *"If length of transaction data (`initcode`) in a create transaction
    * exceeds `MAX_INITCODE_SIZE`, transaction is invalid"* (`ethereum/EIPs` @
    * `dbfa6bee8`, `EIPS/eip-3860.md`, Final, rule 1). **The bound is on a create
    * transaction's data and on nothing else**, so a call carrying a longer
    * payload is admitted at every fork -- reading the bound over every
    * transaction would refuse calls no network refuses, and the data of a call
    * is an argument rather than code.
    *
    * `ethereum/execution-specs` @ `20f7f6271a` writes the pair as
    * `if tx.to == Bytes0(b"") and len(tx.data) > MAX_INIT_CODE_SIZE`
    * (`forks/shanghai/transactions.py:340`), `besu-eth/besu` @ `fdf1247c6d` as
    * `transaction.isContractCreation() && transaction.getPayload().size() >
    * maxInitcodeSize` (`MainnetTransactionValidator.java:131`), and
    * `NethermindEth/nethermind` @ `b92e2a4719` as
    * `tx.IsContractCreation && spec.IsEip3860Enabled && tx.DataLength >
    * spec.MaxInitCodeSize` (`TransactionExtensions.cs:57`).
    *
    * The comparison is strictly greater in all three, so data of exactly the
    * bound is admitted -- which is one of the four cases the document's own test
    * list names.
    */
  private def offersOversizedInitcode(offered: OfferedTransaction, maxInitcodeSize: Option[Int]): Boolean =
    offered.to.isEmpty && maxInitcodeSize.exists(offered.data.length > _)

  /** Whether the tip offered exceeds the total the transaction will pay.
    *
    * Checked before the block's charge is consulted, because it is a property of
    * the transaction alone -- the specification makes it in
    * `validate_transaction` alongside the intrinsic-gas check rather than in
    * `check_transaction` with the rules that read a block
    * (`ethereum/execution-specs` @ `20f7f6271a`
    * `forks/london/transactions.py:334-338`). A transaction failing it is
    * malformed at any charge, including none.
    */
  private def tipExceedsCap(fee: FeeOffer): Boolean = fee match
    case FeeOffer.Fixed(_)                    => false
    case FeeOffer.Capped(maxFee, maxPriority) => maxPriority > maxFee

  /** The identifier the signature was made for, where it names one.
    *
    * A legacy transaction folds it into `v`, so absence covers two situations a
    * caller must not tell apart here: an unprotected signature, which names no
    * chain deliberately, and a `v` that names no scheme at all. The second is
    * an invalid signature and recovery is what says so, which is the same
    * answer the specification gives it.
    */
  private def chainIdOf(transaction: Transaction): Option[UInt64] = transaction match
    case t: Transaction.Legacy =>
      SignatureScheme.of(t.v).toOption.collect { case SignatureScheme.Protected(id) => id }
    case t: Transaction.AccessList => Some(t.chainId)
    case t: Transaction.DynamicFee => Some(t.chainId)
    case t: Transaction.Blob       => Some(t.chainId)
    case t: Transaction.SetCode    => Some(t.chainId)

  private def signedForAnotherChain(transaction: Transaction, chainId: UInt64): Boolean =
    chainIdOf(transaction).exists(_ != chainId)

  /** Whether the SIGNATURE names a chain, which only a legacy one can.
    *
    * EIP-155 folds the identifier into `v` instead of adding a field, so naming
    * a chain is a property of how the signature was made. A typed transaction
    * states `chainId` as a field its envelope requires of it, and every fork
    * that admits such a format admits the field with it -- so reading one here
    * would let a rule about the legacy encoding refuse a format the rule was
    * never about. [[chainIdOf]] reads both because the comparison it feeds is
    * about the value; this is about the encoding, and the two part company on
    * exactly that.
    */
  private def namesChainInSignature(transaction: Transaction): Boolean = transaction match
    case t: Transaction.Legacy =>
      SignatureScheme
        .of(t.v)
        .toOption
        .exists:
          case SignatureScheme.Protected(_) => true
          case SignatureScheme.Unprotected  => false
    case _: Transaction.AccessList => false
    case _: Transaction.DynamicFee => false
    case _: Transaction.Blob       => false
    case _: Transaction.SetCode    => false

  /** What an admitted transaction hands to settlement.
    *
    * The charge is passed in rather than computed here, so that the figure this
    * record carries is the one the branch above compared against the limit. A
    * second call would be a second definition of it.
    */
  private def settling(
      offered: OfferedTransaction,
      intrinsicGas: BigInt,
      baseFeePerGas: BigInt,
      blobGasUsed: BigInt,
      blobGasPrice: Option[BigInt]
  ): AdmittedTransaction =
    AdmittedTransaction(
      sender = offered.sender,
      nonce = offered.nonce,
      gasPrice = offered.fee.effective(baseFeePerGas),
      baseFeePerGas = baseFeePerGas,
      gasLimit = offered.gasLimit,
      to = offered.to,
      value = offered.value,
      data = offered.data,
      accessList = offered.accessList,
      intrinsicGas = intrinsicGas,
      blobGasUsed = blobGasUsed,
      // Zero where the block sets no charge, which is every fork below the
      // first that prices blob gas. It multiplies a blob count that is itself
      // zero at every such fork, so the product is zero either way -- written
      // as the block's answer rather than as a stand-in, because a later reader
      // of this member must not inherit a zero that means absence.
      blobGasPrice = blobGasPrice.getOrElse(BigInt(0)),
      blobVersionedHashes = offered.blobs.map(_.versionedHashes).getOrElse(Seq.empty)
    )
