package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, UInt64}
import org.fukuii.crypto.Secp256k1
import org.fukuii.evm.{GasSchedule, WorldState}
import org.fukuii.types.{Sender, SignatureScheme, Transaction, TransactionType}

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
  */
final case class OfferedTransaction(
    transactionType: TransactionType,
    sender: Address,
    nonce: BigInt,
    gasPrice: BigInt,
    gasLimit: BigInt,
    to: Option[Address],
    value: BigInt,
    data: Bytes
)

/** Whether a transaction may run at all, and why not when it may not. */
enum Admission:

  /** @param transaction
    *   the values settling it spends, which is the whole of what settlement
    *   needs and is produced here so that no caller assembles its own.
    * @param intrinsicGas
    *   what it is charged before any of it runs. Settlement computes the same
    *   figure from the same schedule; it is reported because a caller that
    *   wants it should not have to.
    */
  case Admitted(transaction: AdmittedTransaction, intrinsicGas: BigInt)

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
  * of them. [[admit]] takes its six from `ethereum/execution-specs` @
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
    * @param gasAvailable
    *   what the block has left to give, which is its limit less the gas already
    *   used by the transactions before this one. It is the remainder rather
    *   than the limit because the specification states it that way -- `forks`'
    *   `check_transaction` opens `gas_available = block_env.block_gas_limit -
    *   block_output.block_gas_used` -- and a caller settling one transaction
    *   against an otherwise empty block passes the limit itself.
    */
  def admit(
      offered: OfferedTransaction,
      world: WorldState,
      gasAvailable: BigInt,
      rules: AdmissionRules,
      schedule: GasSchedule
  ): Admission =
    lazy val intrinsic = IntrinsicGas.of(schedule, offered.data, offered.to.isEmpty)
    lazy val counted = world.nonceOf(offered.sender).toBigInt
    lazy val held = world.balanceOf(offered.sender).toBigInt
    lazy val maximumFee = offered.gasLimit * offered.gasPrice
    if !admitsFormat(offered.transactionType, rules) then Admission.Refused(Refusal.TypeNotAdmitted)
    else if intrinsic > offered.gasLimit then Admission.Refused(Refusal.IntrinsicGasTooLow)
    else if offered.nonce >= NonceLimit then Admission.Refused(Refusal.NonceIsMax)
    else if offered.gasLimit > gasAvailable then Admission.Refused(Refusal.GasAllowanceExceeded)
    else if counted != offered.nonce then Admission.Refused(Refusal.NonceMismatch)
    else if held < maximumFee + offered.value then Admission.Refused(Refusal.InsufficientAccountFunds)
    else if world.codeOf(offered.sender).nonEmpty then Admission.Refused(Refusal.SenderNotEoa)
    else Admission.Admitted(settling(offered), intrinsic)

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

  /** What an admitted transaction hands to settlement. */
  private def settling(offered: OfferedTransaction): AdmittedTransaction =
    AdmittedTransaction(
      sender = offered.sender,
      nonce = offered.nonce,
      gasPrice = offered.gasPrice,
      gasLimit = offered.gasLimit,
      to = offered.to,
      value = offered.value,
      data = offered.data
    )
