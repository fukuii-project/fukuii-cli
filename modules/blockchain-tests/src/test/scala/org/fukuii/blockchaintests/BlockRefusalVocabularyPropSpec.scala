package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.{Hash, UInt256, UInt64}
import org.fukuii.consensus.{BlockFault, HeaderFault}
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.ExpectedRejection
import org.fukuii.execution.{BlockRejection, Refusal}
import org.fukuii.rlp.RlpError
import org.fukuii.types.{BlockNonce, Bloom}

/** Whether a refusal this build produced is one a published name states, as
  * tables of named cases with one expected answer each.
  *
  * ==A name's accepting row has a twin that must refuse==
  *
  * A vocabulary that satisfied every name with every fault would pass any row
  * asking only whether a right refusal is accepted. So each name is held against
  * the fault it names and against a neighboring fault it does not, and the
  * second is what shows the mapping discriminates. The row stating alternatives
  * has no twin of its own.
  *
  * **A name held at two positions has a row at each**, and its twin is the
  * neighboring fault that is neither: that is what shows the second position
  * was added as the same rule and not as the family around it.
  *
  * `BlockRefusalVocabularySpec` holds the example of the other member, the
  * names a stated refusal carries that neither half of the vocabulary holds.
  */
class BlockRefusalVocabularyPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def fee(value: Long): UInt256 = UInt256.fromLong(value).fold(error => fail(error.toString), identity)

  private def header(fault: HeaderFault): BlockFault = BlockFault.Header(fault)

  private val GasLimit: BlockFault = header(HeaderFault.GasLimitOutOfBounds(BigInt(4999), BigInt(5000)))

  private val BaseFee: BlockFault = header(HeaderFault.BaseFeeMismatch(fee(1), fee(7)))

  private val A: Hash = EvmFixtures.hash(0x0a)

  private val B: Hash = EvmFixtures.hash(0x0b)

  private val OneBlob: BigInt = BigInt(131072)

  private val NonZeroNonce: BlockNonce =
    BlockNonce.fromHex("0x0102030405060708").fold(error => fail(error.toString), identity)

  private def transaction(reason: Refusal): BlockFault =
    BlockFault.TransactionRefused(BlockRejection(index = 0, reason = reason, unbuilt = None))

  /** A key no published corpus states, so it can never be added and stop being
    * the name a vocabulary lacks.
    */
  private val Unpublished: String = "BlockException.NOT_A_PUBLISHED_NAME"

  private val Rows = Table(
    ("case", "stated", "fault", "satisfied"),
    ("a gas limit out of bounds, for the gas-limit name", Set("BlockException.INVALID_GASLIMIT"), GasLimit, true),
    ("a base fee mismatch, for the gas-limit name", Set("BlockException.INVALID_GASLIMIT"), BaseFee, false),
    ("a base fee mismatch, for the base-fee name", Set("BlockException.INVALID_BASEFEE_PER_GAS"), BaseFee, true),
    (
      "a missing base fee, for the base-fee name, which names a miscalculated one",
      Set("BlockException.INVALID_BASEFEE_PER_GAS"),
      header(HeaderFault.BaseFeeMissing),
      false
    ),
    (
      "a transaction refused for the reason its name maps to",
      Set("TransactionException.INTRINSIC_GAS_TOO_LOW"),
      transaction(Refusal.IntrinsicGasTooLow),
      true
    ),
    (
      "a transaction refused for another reason",
      Set("TransactionException.INTRINSIC_GAS_TOO_LOW"),
      transaction(Refusal.InsufficientAccountFunds),
      false
    ),
    ("a header fault, for a transaction name", Set("TransactionException.INTRINSIC_GAS_TOO_LOW"), GasLimit, false),
    (
      "a refusal under one of the alternatives a case states",
      Set("TransactionException.INVALID_SIGNATURE_VRS", "TransactionException.INVALID_CHAINID"),
      transaction(Refusal.WrongChainId),
      true
    ),
    ("a header fault, under a name nothing holds", Set(Unpublished), GasLimit, false),
    (
      "a transaction refusal, under a name nothing holds",
      Set(Unpublished),
      transaction(Refusal.IntrinsicGasTooLow),
      false
    ),
    (
      "a count below the sender's, for the name for one below",
      Set("TransactionException.NONCE_MISMATCH_TOO_LOW"),
      transaction(Refusal.NonceTooLow),
      true
    ),
    (
      "a count above the sender's, for the name for one below",
      Set("TransactionException.NONCE_MISMATCH_TOO_LOW"),
      transaction(Refusal.NonceTooHigh),
      false
    ),
    (
      "a count above the sender's, for the name for one above",
      Set("TransactionException.NONCE_MISMATCH_TOO_HIGH"),
      transaction(Refusal.NonceTooHigh),
      true
    ),
    (
      "a count below the sender's, for the name for one above",
      Set("TransactionException.NONCE_MISMATCH_TOO_HIGH"),
      transaction(Refusal.NonceTooLow),
      false
    ),
    (
      "a withdrawals root mismatch, for the withdrawals-root name",
      Set("BlockException.INVALID_WITHDRAWALS_ROOT"),
      BlockFault.WithdrawalsRootMismatch(A, B),
      true
    ),
    (
      "a missing withdrawals list, for the withdrawals-root name",
      Set("BlockException.INVALID_WITHDRAWALS_ROOT"),
      BlockFault.WithdrawalsMissing,
      false
    ),
    (
      "an excess mismatch, for the excess name",
      Set("BlockException.INCORRECT_EXCESS_BLOB_GAS"),
      header(HeaderFault.ExcessBlobGasMismatch(UInt64.Zero, OneBlob)),
      true
    ),
    (
      "a missing blob-gas account, for the excess name",
      Set("BlockException.INCORRECT_EXCESS_BLOB_GAS"),
      header(HeaderFault.BlobGasMissing),
      false
    ),
    (
      "a spend other than the body's, for the spend name",
      Set("BlockException.INCORRECT_BLOB_GAS_USED"),
      BlockFault.BlobGasUsedMismatch(OneBlob, BigInt(0)),
      true
    ),
    (
      "a spend of no whole number of blobs, for the spend name",
      Set("BlockException.INCORRECT_BLOB_GAS_USED"),
      header(HeaderFault.BlobGasUsedNotWholeBlobs(UInt64.fromBits(1L))),
      true
    ),
    (
      "a spend above the limit, for the spend name",
      Set("BlockException.INCORRECT_BLOB_GAS_USED"),
      header(HeaderFault.BlobGasUsedAboveLimit(UInt64.fromBits(917504L), BigInt(786432))),
      false
    ),
    (
      "a spend above the limit, for the limit name",
      Set("BlockException.BLOB_GAS_USED_ABOVE_LIMIT"),
      header(HeaderFault.BlobGasUsedAboveLimit(UInt64.fromBits(917504L), BigInt(786432))),
      true
    ),
    (
      "a spend of no whole number of blobs, for the limit name",
      Set("BlockException.BLOB_GAS_USED_ABOVE_LIMIT"),
      header(HeaderFault.BlobGasUsedNotWholeBlobs(UInt64.fromBits(1L))),
      false
    ),
    (
      "a header spending above the limit, for the name for transactions spending above it",
      Set("TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED"),
      header(HeaderFault.BlobGasUsedAboveLimit(UInt64.fromBits(917504L), BigInt(786432))),
      true
    ),
    (
      "a transaction spending above the limit, for the name for transactions spending above it",
      Set("TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED"),
      transaction(Refusal.BlobGasAllowanceExceeded),
      true
    ),
    (
      "a spend of no whole number of blobs, for the name for transactions spending above the limit",
      Set("TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED"),
      header(HeaderFault.BlobGasUsedNotWholeBlobs(UInt64.fromBits(1L))),
      false
    ),
    (
      "a blob-gas account below any blob proposal, for the format name",
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      header(HeaderFault.BlobGasUnexpected(UInt64.Zero)),
      true
    ),
    (
      "a missing beacon root, for the format name",
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      header(HeaderFault.ParentBeaconBlockRootMissing),
      true
    ),
    ("a base fee mismatch, for the format name", Set("BlockException.INCORRECT_BLOCK_FORMAT"), BaseFee, false),
    (
      "extra data above the limit, for the legacy name for it",
      Set("ExtraDataTooBig"),
      header(HeaderFault.ExtraDataAboveLimit(33, 32)),
      true
    ),
    ("a gas limit out of bounds, for the legacy extra-data name", Set("ExtraDataTooBig"), GasLimit, false),
    (
      "a block no later than its parent, for the legacy name for it",
      Set("InvalidTimestampOlderParent"),
      header(HeaderFault.TimestampNotAfterParent(BigInt(1), BigInt(1))),
      true
    ),
    (
      "a block that is not its parent's successor, for the legacy timestamp name",
      Set("InvalidTimestampOlderParent"),
      header(HeaderFault.NumberNotParentSuccessor(BigInt(2), BigInt(0))),
      false
    ),
    (
      "a block that is not its parent's successor, for the legacy name for it",
      Set("InvalidNumber"),
      header(HeaderFault.NumberNotParentSuccessor(BigInt(2), BigInt(0))),
      true
    ),
    (
      "a block no later than its parent, for the legacy number name",
      Set("InvalidNumber"),
      header(HeaderFault.TimestampNotAfterParent(BigInt(1), BigInt(1))),
      false
    ),
    ("a gas limit out of bounds, for the legacy bound name", Set("InvalidGasLimit2"), GasLimit, true),
    (
      "gas used above the limit, for the legacy bound name",
      Set("InvalidGasLimit2"),
      header(HeaderFault.GasUsedAboveLimit(BigInt(22027), BigInt(0))),
      false
    ),
    (
      "a gas limit out of bounds, for the legacy name for the maximum",
      Set("InvalidGasLimit"),
      GasLimit,
      false
    ),
    (
      "a gas limit above the maximum, for the legacy name for it",
      Set("InvalidGasLimit"),
      header(HeaderFault.GasLimitAboveMaximum(BigInt(1) << 63, BigInt(Long.MaxValue))),
      true
    ),
    (
      "a gas limit above the maximum, for the legacy bound name",
      Set("InvalidGasLimit2"),
      header(HeaderFault.GasLimitAboveMaximum(BigInt(1) << 63, BigInt(Long.MaxValue))),
      false
    ),
    (
      "a gas limit above the maximum, for the generated gas-limit name",
      Set("BlockException.INVALID_GASLIMIT"),
      header(HeaderFault.GasLimitAboveMaximum(BigInt(1) << 63, BigInt(Long.MaxValue))),
      false
    ),
    ("a wrong parent, for the legacy parent name", Set("UnknownParent"), BlockFault.ParentHashMismatch(A, B), true),
    (
      "a wrong parent, for the second legacy parent name",
      Set("UnknownParent2"),
      BlockFault.ParentHashMismatch(A, B),
      true
    ),
    ("a header fault, for the legacy parent name", Set("UnknownParent"), GasLimit, false),
    (
      "a transactions root mismatch, for the legacy name for it",
      Set("InvalidTransactionsRoot"),
      BlockFault.TransactionsRootMismatch(A, B),
      true
    ),
    (
      "an ommers hash mismatch, for the legacy transactions-root name",
      Set("InvalidTransactionsRoot"),
      BlockFault.OmmersHashMismatch(A, B),
      false
    ),
    (
      "gas used other than produced, for the legacy name for it",
      Set("InvalidGasUsed"),
      BlockFault.GasUsedMismatch(BigInt(0), BigInt(22027)),
      true
    ),
    (
      "gas used above the limit, for the legacy gas-used name",
      Set("InvalidGasUsed"),
      header(HeaderFault.GasUsedAboveLimit(BigInt(22027), BigInt(0))),
      false
    ),
    (
      "a bloom other than produced, for the legacy name for it",
      Set("InvalidLogBloom"),
      BlockFault.LogsBloomMismatch(Bloom.Empty, Bloom.Empty),
      true
    ),
    (
      "a receipts root mismatch, for the legacy bloom name",
      Set("InvalidLogBloom"),
      BlockFault.ReceiptsRootMismatch(A, B),
      false
    ),
    (
      "a receipts root mismatch, for the legacy name for it",
      Set("InvalidReceiptsStateRoot"),
      BlockFault.ReceiptsRootMismatch(A, B),
      true
    ),
    (
      "a state root mismatch, for the legacy receipts-root name",
      Set("InvalidReceiptsStateRoot"),
      BlockFault.StateRootMismatch(A, B),
      false
    ),
    (
      "a state root mismatch, for the legacy name for it",
      Set("InvalidStateRoot"),
      BlockFault.StateRootMismatch(A, B),
      true
    ),
    (
      "a receipts root mismatch, for the legacy state-root name",
      Set("InvalidStateRoot"),
      BlockFault.ReceiptsRootMismatch(A, B),
      false
    ),
    (
      "a difficulty other than zero, for the name for EIP-3675's constants",
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      true
    ),
    (
      "an ommers commitment other than the empty list's, for the name for EIP-3675's constants",
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.OmmersNotEmpty(A)),
      true
    ),
    (
      "a nonce other than zero, for the name for EIP-3675's constants",
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.NonceNotFixed(NonZeroNonce)),
      true
    ),
    (
      "a seal of another shape, for the name for EIP-3675's constants",
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.SealShapeUnexpected),
      false
    ),
    (
      "a difficulty other than zero, for the name for it",
      Set("PostParisDifficultyIsNot0"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      true
    ),
    (
      "an ommers commitment other than the empty list's, for the difficulty name",
      Set("PostParisDifficultyIsNot0"),
      header(HeaderFault.OmmersNotEmpty(A)),
      false
    ),
    (
      "an ommers commitment other than the empty list's, for the name for it",
      Set("PostParisUncleHashIsNotEmpty"),
      header(HeaderFault.OmmersNotEmpty(A)),
      true
    ),
    (
      "a difficulty other than zero, for the ommers name",
      Set("PostParisUncleHashIsNotEmpty"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      false
    )
  )

  private val HeaderShape: DecodeFailure = DecodeFailure.Header(RlpError.WrongArity(19, 18))

  private val BodyShape: DecodeFailure = DecodeFailure.Block(RlpError.ExpectedSequence)

  private val NotRlp: DecodeFailure = DecodeFailure.Block(RlpError.Truncated(2, 1))

  private val Undecodable = Table(
    ("case", "stated", "failure", "satisfied"),
    (
      "a header of no fork's shape, for the structures name",
      Set("BlockException.RLP_STRUCTURES_ENCODING"),
      HeaderShape,
      true
    ),
    (
      "a header of no fork's shape, for the format name",
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      HeaderShape,
      true
    ),
    (
      "a header field of the wrong width, for the format name, which holds any typed header failure",
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      DecodeFailure.Header(RlpError.WrongWidth(20, 0)),
      true
    ),
    (
      "a body of no block's shape, for the structures name",
      Set("BlockException.RLP_STRUCTURES_ENCODING"),
      BodyShape,
      true
    ),
    ("a body of no block's shape, for the format name", Set("BlockException.INCORRECT_BLOCK_FORMAT"), BodyShape, false),
    ("bytes that are not RLP, for the structures name", Set("BlockException.RLP_STRUCTURES_ENCODING"), NotRlp, false),
    (
      "a header of no fork's shape, for a transaction's name",
      Set("TransactionException.TYPE_3_TX_WITH_FULL_BLOBS"),
      HeaderShape,
      false
    ),
    ("a header of no fork's shape, for a rule's name", Set("BlockException.INVALID_GASLIMIT"), HeaderShape, false)
  )

  property("a refusal satisfies exactly the stated names that map to its rule") {
    forAll(Rows) { (label, stated, fault, satisfied) =>
      assert(
        BlockRefusalVocabulary.satisfies(ExpectedRejection(stated), fault) == satisfied,
        label + ": expected " + (if satisfied then "satisfied" else "refused") + " for " + fault.toString
      )
    }
  }

  property("a block this build cannot decode satisfies exactly the stated names whose rule that is") {
    forAll(Undecodable) { (label, stated, failure, satisfied) =>
      assert(
        BlockRefusalVocabulary.satisfiedByUndecodable(ExpectedRejection(stated), failure) == satisfied,
        label + ": expected " + (if satisfied then "satisfied" else "refused") + " for " + failure.toString
      )
    }
  }
