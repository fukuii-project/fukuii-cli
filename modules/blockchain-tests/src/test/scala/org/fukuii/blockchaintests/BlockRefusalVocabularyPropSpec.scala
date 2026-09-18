package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.consensus.{BlockFault, HeaderFault}
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.ExpectedRejection
import org.fukuii.execution.{BlockRejection, Refusal}
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.fukuii.types.{AccessTuple, BaseFeeTail, Block, BlockHeader, BlockNonce, Bloom, Seal, Transaction}

/** Whether a refusal this build produced is one a published name states, in
  * the names of the tool that filled its corpus, as tables of named cases with
  * one expected answer each.
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
  * ==A name held by one tool is refused in another's corpus==
  *
  * The rows naming a tool other than the one a name belongs to are the
  * snapshots told apart: `InvalidGasLimit` holds the bound in testeth's names
  * and not in retesteth's, and a name one tool writes satisfies nothing in a
  * corpus the other filled.
  *
  * ==One name is decided by the refused header as well, and its table varies it==
  *
  * `3675PoSBlockRejected` is satisfied by a difficulty refusal of a header in
  * EIP-3675's form, as retesteth classifies one. Its rows break each condition
  * of that form alone -- the field count, the difficulty, the ommers commitment,
  * the nonce -- and pair the form with a refusal for another rule and with each
  * other tool, so no condition can be dropped without a row refusing. Every
  * other name is decided by the fault alone, and its rows are asked over a
  * header outside the form.
  *
  * ==Where a block stopped decoding is asked too, of bytes written for each shape==
  *
  * `TransactionException.TYPE_3_TX_CONTRACT_CREATION` is satisfied where a
  * block stops decoding at a blob transaction's empty recipient, so what places
  * that stop is a table of its own: one block carrying such a transaction,
  * changed one way per row -- the transaction placed second, its recipient
  * restored or one byte short, an empty address elsewhere in it, another
  * format's empty recipient, a field before the recipient that stops the decode
  * first, a transaction before it that does, a header that does -- each row
  * stating where the decode stopped. No transaction here is signed, because
  * where a decode stops reads no signature.
  *
  * `BlockRefusalVocabularySpec` holds the examples of the other member, the
  * names a stated refusal carries that no half of a tool's vocabulary holds.
  */
class BlockRefusalVocabularyPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def fee(value: Long): UInt256 = UInt256.fromLong(value).fold(error => fail(error.toString), identity)

  private def header(fault: HeaderFault): BlockFault = BlockFault.Header(fault)

  private val GasLimit: BlockFault = header(HeaderFault.GasLimitOutOfBounds(BigInt(4999), BigInt(5000)))

  private val BaseFee: BlockFault = header(HeaderFault.BaseFeeMismatch(fee(1), fee(7)))

  private val AboveMaximum: BlockFault = header(
    HeaderFault.GasLimitAboveMaximum(BigInt(1) << 63, BigInt(Long.MaxValue))
  )

  private val GasUsedAbove: BlockFault = header(HeaderFault.GasUsedAboveLimit(BigInt(22027), BigInt(0)))

  private val Difficulty: BlockFault = header(HeaderFault.DifficultyMismatch(fee(10000), fee(131072)))

  private val Early: BlockFault = header(HeaderFault.TimestampNotAfterParent(BigInt(1), BigInt(1)))

  private val Unsucceeding: BlockFault = header(HeaderFault.NumberNotParentSuccessor(BigInt(2), BigInt(0)))

  private val A: Hash = EvmFixtures.hash(0x0a)

  private val B: Hash = EvmFixtures.hash(0x0b)

  private val OneBlob: BigInt = BigInt(131072)

  private val NonZeroNonce: BlockNonce =
    BlockNonce.fromHex("0x0102030405060708").fold(error => fail(error.toString), identity)

  private def transaction(reason: Refusal): BlockFault =
    BlockFault.TransactionRefused(BlockRejection.RefusedTransaction(index = 0, reason = reason, unbuilt = None))

  /** A key no published corpus states, so it can never be added and stop being
    * the name a vocabulary lacks.
    */
  private val Unpublished: String = "BlockException.NOT_A_PUBLISHED_NAME"

  private val Ids: FillingTool = FillingTool.ExecutionSpecs

  private val Retesteth: FillingTool = FillingTool.Retesteth

  private val Testeth: FillingTool = FillingTool.Testeth

  private val Rows = Table(
    ("case", "tool", "stated", "fault", "satisfied"),
    ("a gas limit out of bounds, for the gas-limit name", Ids, Set("BlockException.INVALID_GASLIMIT"), GasLimit, true),
    ("a base fee mismatch, for the gas-limit name", Ids, Set("BlockException.INVALID_GASLIMIT"), BaseFee, false),
    ("a base fee mismatch, for the base-fee name", Ids, Set("BlockException.INVALID_BASEFEE_PER_GAS"), BaseFee, true),
    (
      "a missing base fee, for the base-fee name, which names a miscalculated one",
      Ids,
      Set("BlockException.INVALID_BASEFEE_PER_GAS"),
      header(HeaderFault.BaseFeeMissing),
      false
    ),
    (
      "a transaction refused for the reason its name maps to",
      Ids,
      Set("TransactionException.INTRINSIC_GAS_TOO_LOW"),
      transaction(Refusal.IntrinsicGasTooLow),
      true
    ),
    (
      "a transaction refused for another reason",
      Ids,
      Set("TransactionException.INTRINSIC_GAS_TOO_LOW"),
      transaction(Refusal.InsufficientAccountFunds),
      false
    ),
    ("a header fault, for a transaction name", Ids, Set("TransactionException.INTRINSIC_GAS_TOO_LOW"), GasLimit, false),
    (
      "a refusal under one of the alternatives a case states",
      Ids,
      Set("TransactionException.INVALID_SIGNATURE_VRS", "TransactionException.INVALID_CHAINID"),
      transaction(Refusal.WrongChainId),
      true
    ),
    ("a header fault, under a name nothing holds", Ids, Set(Unpublished), GasLimit, false),
    (
      "a transaction refusal, under a name nothing holds",
      Ids,
      Set(Unpublished),
      transaction(Refusal.IntrinsicGasTooLow),
      false
    ),
    (
      "a count below the sender's, for the name for one below",
      Ids,
      Set("TransactionException.NONCE_MISMATCH_TOO_LOW"),
      transaction(Refusal.NonceTooLow),
      true
    ),
    (
      "a count above the sender's, for the name for one below",
      Ids,
      Set("TransactionException.NONCE_MISMATCH_TOO_LOW"),
      transaction(Refusal.NonceTooHigh),
      false
    ),
    (
      "a count above the sender's, for the name for one above",
      Ids,
      Set("TransactionException.NONCE_MISMATCH_TOO_HIGH"),
      transaction(Refusal.NonceTooHigh),
      true
    ),
    (
      "a count below the sender's, for the name for one above",
      Ids,
      Set("TransactionException.NONCE_MISMATCH_TOO_HIGH"),
      transaction(Refusal.NonceTooLow),
      false
    ),
    (
      "a withdrawals root mismatch, for the withdrawals-root name",
      Ids,
      Set("BlockException.INVALID_WITHDRAWALS_ROOT"),
      BlockFault.WithdrawalsRootMismatch(A, B),
      true
    ),
    (
      "a missing withdrawals list, for the withdrawals-root name",
      Ids,
      Set("BlockException.INVALID_WITHDRAWALS_ROOT"),
      BlockFault.WithdrawalsMissing,
      false
    ),
    (
      "an excess mismatch, for the excess name",
      Ids,
      Set("BlockException.INCORRECT_EXCESS_BLOB_GAS"),
      header(HeaderFault.ExcessBlobGasMismatch(UInt64.Zero, OneBlob)),
      true
    ),
    (
      "a missing blob-gas account, for the excess name",
      Ids,
      Set("BlockException.INCORRECT_EXCESS_BLOB_GAS"),
      header(HeaderFault.BlobGasMissing),
      false
    ),
    (
      "a spend other than the body's, for the spend name",
      Ids,
      Set("BlockException.INCORRECT_BLOB_GAS_USED"),
      BlockFault.BlobGasUsedMismatch(OneBlob, BigInt(0)),
      true
    ),
    (
      "a spend of no whole number of blobs, for the spend name",
      Ids,
      Set("BlockException.INCORRECT_BLOB_GAS_USED"),
      header(HeaderFault.BlobGasUsedNotWholeBlobs(UInt64.fromBits(1L))),
      true
    ),
    (
      "a spend above the limit, for the spend name",
      Ids,
      Set("BlockException.INCORRECT_BLOB_GAS_USED"),
      header(HeaderFault.BlobGasUsedAboveLimit(UInt64.fromBits(917504L), BigInt(786432))),
      false
    ),
    (
      "a spend above the limit, for the limit name",
      Ids,
      Set("BlockException.BLOB_GAS_USED_ABOVE_LIMIT"),
      header(HeaderFault.BlobGasUsedAboveLimit(UInt64.fromBits(917504L), BigInt(786432))),
      true
    ),
    (
      "a spend of no whole number of blobs, for the limit name",
      Ids,
      Set("BlockException.BLOB_GAS_USED_ABOVE_LIMIT"),
      header(HeaderFault.BlobGasUsedNotWholeBlobs(UInt64.fromBits(1L))),
      false
    ),
    (
      "a header spending above the limit, for the name for transactions spending above it",
      Ids,
      Set("TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED"),
      header(HeaderFault.BlobGasUsedAboveLimit(UInt64.fromBits(917504L), BigInt(786432))),
      true
    ),
    (
      "a transaction spending above the limit, for the name for transactions spending above it",
      Ids,
      Set("TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED"),
      transaction(Refusal.BlobGasAllowanceExceeded),
      true
    ),
    (
      "a spend of no whole number of blobs, for the name for transactions spending above the limit",
      Ids,
      Set("TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED"),
      header(HeaderFault.BlobGasUsedNotWholeBlobs(UInt64.fromBits(1L))),
      false
    ),
    (
      "a blob-gas account below any blob proposal, for the format name",
      Ids,
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      header(HeaderFault.BlobGasUnexpected(UInt64.Zero)),
      true
    ),
    (
      "a missing beacon root, for the format name",
      Ids,
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      header(HeaderFault.ParentBeaconBlockRootMissing),
      true
    ),
    ("a base fee mismatch, for the format name", Ids, Set("BlockException.INCORRECT_BLOCK_FORMAT"), BaseFee, false),
    // ── retesteth's names ──
    (
      "extra data above the limit, for retesteth's name for it",
      Retesteth,
      Set("ExtraDataTooBig"),
      header(HeaderFault.ExtraDataAboveLimit(33, 32)),
      true
    ),
    ("a gas limit out of bounds, for retesteth's extra-data name", Retesteth, Set("ExtraDataTooBig"), GasLimit, false),
    (
      "a block no later than its parent, for retesteth's name for it",
      Retesteth,
      Set("InvalidTimestampOlderParent"),
      Early,
      true
    ),
    (
      "a block that is not its parent's successor, for retesteth's timestamp name",
      Retesteth,
      Set("InvalidTimestampOlderParent"),
      Unsucceeding,
      false
    ),
    (
      "a block that is not its parent's successor, for retesteth's name for it",
      Retesteth,
      Set("InvalidNumber"),
      Unsucceeding,
      true
    ),
    ("a block no later than its parent, for retesteth's number name", Retesteth, Set("InvalidNumber"), Early, false),
    ("a gas limit out of bounds, for retesteth's bound name", Retesteth, Set("InvalidGasLimit2"), GasLimit, true),
    ("gas used above the limit, for retesteth's bound name", Retesteth, Set("InvalidGasLimit2"), GasUsedAbove, false),
    (
      "a gas limit out of bounds, for retesteth's name for the maximum",
      Retesteth,
      Set("InvalidGasLimit"),
      GasLimit,
      false
    ),
    (
      "a gas limit above the maximum, for retesteth's name for it",
      Retesteth,
      Set("InvalidGasLimit"),
      AboveMaximum,
      true
    ),
    (
      "a gas limit above the maximum, for retesteth's bound name",
      Retesteth,
      Set("InvalidGasLimit2"),
      AboveMaximum,
      false
    ),
    (
      "a gas limit above the maximum, for the generated gas-limit name",
      Ids,
      Set("BlockException.INVALID_GASLIMIT"),
      AboveMaximum,
      false
    ),
    (
      "a difficulty other than its parent requires, for retesteth's name for it",
      Retesteth,
      Set("InvalidDifficulty"),
      Difficulty,
      true
    ),
    (
      "a difficulty other than the merge's constant, for retesteth's difficulty name",
      Retesteth,
      Set("InvalidDifficulty"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      false
    ),
    (
      "a wrong parent, for retesteth's parent name",
      Retesteth,
      Set("UnknownParent"),
      BlockFault.ParentHashMismatch(A, B),
      true
    ),
    (
      "a wrong parent, for retesteth's second parent name",
      Retesteth,
      Set("UnknownParent2"),
      BlockFault.ParentHashMismatch(A, B),
      true
    ),
    ("a header fault, for retesteth's parent name", Retesteth, Set("UnknownParent"), GasLimit, false),
    (
      "an ommers commitment other than the body's, for retesteth's name for it",
      Retesteth,
      Set("InvalidUnclesHash"),
      BlockFault.OmmersHashMismatch(A, B),
      true
    ),
    (
      "an ommers commitment other than the merge's constant, for retesteth's ommers name",
      Retesteth,
      Set("InvalidUnclesHash"),
      header(HeaderFault.OmmersNotEmpty(A)),
      false
    ),
    (
      "a transactions root mismatch, for retesteth's name for it",
      Retesteth,
      Set("InvalidTransactionsRoot"),
      BlockFault.TransactionsRootMismatch(A, B),
      true
    ),
    (
      "an ommers hash mismatch, for retesteth's transactions-root name",
      Retesteth,
      Set("InvalidTransactionsRoot"),
      BlockFault.OmmersHashMismatch(A, B),
      false
    ),
    (
      "gas used other than produced, for retesteth's name for it",
      Retesteth,
      Set("InvalidGasUsed"),
      BlockFault.GasUsedMismatch(BigInt(0), BigInt(22027)),
      true
    ),
    ("gas used above the limit, for retesteth's gas-used name", Retesteth, Set("InvalidGasUsed"), GasUsedAbove, false),
    (
      "a bloom other than produced, for retesteth's name for it",
      Retesteth,
      Set("InvalidLogBloom"),
      BlockFault.LogsBloomMismatch(Bloom.Empty, Bloom.Empty),
      true
    ),
    (
      "a receipts root mismatch, for retesteth's bloom name",
      Retesteth,
      Set("InvalidLogBloom"),
      BlockFault.ReceiptsRootMismatch(A, B),
      false
    ),
    (
      "a receipts root mismatch, for retesteth's name for it",
      Retesteth,
      Set("InvalidReceiptsStateRoot"),
      BlockFault.ReceiptsRootMismatch(A, B),
      true
    ),
    (
      "a state root mismatch, for retesteth's receipts-root name",
      Retesteth,
      Set("InvalidReceiptsStateRoot"),
      BlockFault.StateRootMismatch(A, B),
      false
    ),
    (
      "a state root mismatch, for retesteth's name for it",
      Retesteth,
      Set("InvalidStateRoot"),
      BlockFault.StateRootMismatch(A, B),
      true
    ),
    (
      "a receipts root mismatch, for retesteth's state-root name",
      Retesteth,
      Set("InvalidStateRoot"),
      BlockFault.ReceiptsRootMismatch(A, B),
      false
    ),
    (
      "a difficulty other than zero, for the name for EIP-3675's constants",
      Retesteth,
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      true
    ),
    (
      "an ommers commitment other than the empty list's, for the name for EIP-3675's constants",
      Retesteth,
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.OmmersNotEmpty(A)),
      true
    ),
    (
      "a nonce other than zero, for the name for EIP-3675's constants",
      Retesteth,
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.NonceNotFixed(NonZeroNonce)),
      true
    ),
    (
      "a seal of another shape, for the name for EIP-3675's constants",
      Retesteth,
      Set("3675PreParis1559BlockRejected"),
      header(HeaderFault.SealShapeUnexpected),
      false
    ),
    (
      "a difficulty other than its parent requires, for the name for EIP-3675's constants",
      Retesteth,
      Set("3675PreParis1559BlockRejected"),
      Difficulty,
      false
    ),
    (
      "a difficulty other than zero, for the name for it",
      Retesteth,
      Set("PostParisDifficultyIsNot0"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      true
    ),
    (
      "an ommers commitment other than the empty list's, for the difficulty name",
      Retesteth,
      Set("PostParisDifficultyIsNot0"),
      header(HeaderFault.OmmersNotEmpty(A)),
      false
    ),
    (
      "an ommers commitment other than the empty list's, for the name for it",
      Retesteth,
      Set("PostParisUncleHashIsNotEmpty"),
      header(HeaderFault.OmmersNotEmpty(A)),
      true
    ),
    (
      "a difficulty other than zero, for the ommers name",
      Retesteth,
      Set("PostParisUncleHashIsNotEmpty"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      false
    ),
    // ── testeth's names ──
    (
      "extra data above the limit, for testeth's name for it",
      Testeth,
      Set("ExtraDataTooBig"),
      header(HeaderFault.ExtraDataAboveLimit(1024, 32)),
      true
    ),
    ("a gas limit out of bounds, for testeth's extra-data name", Testeth, Set("ExtraDataTooBig"), GasLimit, false),
    (
      "a difficulty other than its parent requires, for testeth's name for it",
      Testeth,
      Set("InvalidDifficulty"),
      Difficulty,
      true
    ),
    (
      "a difficulty other than the merge's constant, for testeth's difficulty name",
      Testeth,
      Set("InvalidDifficulty"),
      header(HeaderFault.DifficultyNotFixed(fee(10000))),
      false
    ),
    (
      "a gas limit above the maximum, for testeth's gas-limit name",
      Testeth,
      Set("InvalidGasLimit"),
      AboveMaximum,
      true
    ),
    (
      "a gas limit out of bounds, for testeth's gas-limit name, which holds the bound too",
      Testeth,
      Set("InvalidGasLimit"),
      GasLimit,
      true
    ),
    ("gas used above the limit, for testeth's gas-limit name", Testeth, Set("InvalidGasLimit"), GasUsedAbove, false),
    ("gas used above the limit, for testeth's name for it", Testeth, Set("TooMuchGasUsed"), GasUsedAbove, true),
    (
      "gas used other than produced, for testeth's name for gas used above the limit",
      Testeth,
      Set("TooMuchGasUsed"),
      BlockFault.GasUsedMismatch(BigInt(0), BigInt(22027)),
      false
    ),
    ("a block no later than its parent, for testeth's name for it", Testeth, Set("InvalidTimestamp"), Early, true),
    (
      "a block that is not its parent's successor, for testeth's timestamp name",
      Testeth,
      Set("InvalidTimestamp"),
      Unsucceeding,
      false
    ),
    (
      "a block that is not its parent's successor, for testeth's name for it",
      Testeth,
      Set("InvalidNumber"),
      Unsucceeding,
      true
    ),
    ("a block no later than its parent, for testeth's number name", Testeth, Set("InvalidNumber"), Early, false),
    (
      "a wrong parent, for testeth's parent name",
      Testeth,
      Set("UnknownParent"),
      BlockFault.ParentHashMismatch(A, B),
      true
    ),
    ("a header fault, for testeth's parent name", Testeth, Set("UnknownParent"), GasLimit, false),
    (
      "an ommers commitment other than the body's, for testeth's name for it",
      Testeth,
      Set("InvalidUnclesHash"),
      BlockFault.OmmersHashMismatch(A, B),
      true
    ),
    (
      "a transactions root mismatch, for testeth's ommers name",
      Testeth,
      Set("InvalidUnclesHash"),
      BlockFault.TransactionsRootMismatch(A, B),
      false
    ),
    (
      "a transactions root mismatch, for testeth's name for it",
      Testeth,
      Set("InvalidTransactionsRoot"),
      BlockFault.TransactionsRootMismatch(A, B),
      true
    ),
    (
      "an ommers hash mismatch, for testeth's transactions-root name",
      Testeth,
      Set("InvalidTransactionsRoot"),
      BlockFault.OmmersHashMismatch(A, B),
      false
    ),
    (
      "gas used other than produced, for testeth's name for it",
      Testeth,
      Set("InvalidGasUsed"),
      BlockFault.GasUsedMismatch(BigInt(0), BigInt(22027)),
      true
    ),
    ("gas used above the limit, for testeth's gas-used name", Testeth, Set("InvalidGasUsed"), GasUsedAbove, false),
    (
      "a bloom other than produced, for testeth's name for it",
      Testeth,
      Set("InvalidLogBloom"),
      BlockFault.LogsBloomMismatch(Bloom.Empty, Bloom.Empty),
      true
    ),
    (
      "a receipts root mismatch, for testeth's bloom name",
      Testeth,
      Set("InvalidLogBloom"),
      BlockFault.ReceiptsRootMismatch(A, B),
      false
    ),
    (
      "a receipts root mismatch, for testeth's name for it",
      Testeth,
      Set("InvalidReceiptsStateRoot"),
      BlockFault.ReceiptsRootMismatch(A, B),
      true
    ),
    (
      "a state root mismatch, for testeth's receipts-root name",
      Testeth,
      Set("InvalidReceiptsStateRoot"),
      BlockFault.StateRootMismatch(A, B),
      false
    ),
    (
      "a state root mismatch, for testeth's name for it",
      Testeth,
      Set("InvalidStateRoot"),
      BlockFault.StateRootMismatch(A, B),
      true
    ),
    (
      "a receipts root mismatch, for testeth's state-root name",
      Testeth,
      Set("InvalidStateRoot"),
      BlockFault.ReceiptsRootMismatch(A, B),
      false
    ),
    // ── one tool's name, in a corpus another filled ──
    (
      "a block no later than its parent, for testeth's timestamp name, in retesteth's corpus",
      Retesteth,
      Set("InvalidTimestamp"),
      Early,
      false
    ),
    (
      "gas used above the limit, for testeth's name for it, in retesteth's corpus",
      Retesteth,
      Set("TooMuchGasUsed"),
      GasUsedAbove,
      false
    ),
    (
      "a gas limit out of bounds, for retesteth's bound name, in testeth's corpus",
      Testeth,
      Set("InvalidGasLimit2"),
      GasLimit,
      false
    ),
    (
      "a block no later than its parent, for retesteth's timestamp name, in testeth's corpus",
      Testeth,
      Set("InvalidTimestampOlderParent"),
      Early,
      false
    ),
    (
      "a wrong parent, for retesteth's second parent name, in testeth's corpus",
      Testeth,
      Set("UnknownParent2"),
      BlockFault.ParentHashMismatch(A, B),
      false
    ),
    (
      "a gas limit out of bounds, for the generated name, in testeth's corpus",
      Testeth,
      Set("BlockException.INVALID_GASLIMIT"),
      GasLimit,
      false
    ),
    (
      "a gas limit out of bounds, for retesteth's bound name, in the generated tier",
      Ids,
      Set("InvalidGasLimit2"),
      GasLimit,
      false
    ),
    (
      "a transaction refused for the reason its generated name maps to, in retesteth's corpus",
      Retesteth,
      Set("TransactionException.INTRINSIC_GAS_TOO_LOW"),
      transaction(Refusal.IntrinsicGasTooLow),
      false
    )
  )

  private val HeaderShape: DecodeFailure = DecodeFailure.Header(RlpError.WrongArity(19, 18))

  private val BodyShape: DecodeFailure = DecodeFailure.Block(RlpError.ExpectedSequence)

  private val NotRlp: DecodeFailure = DecodeFailure.Block(RlpError.Truncated(2, 1))

  private val StoppedAtBlobRecipient: DecodeFailure = DecodeFailure.BlobRecipientEmpty(0)

  /** An empty field where a twenty-byte address is required, which is the error a
    * blob transaction's empty recipient reports and so does any other empty
    * address.
    */
  private val EmptyAddress: RlpError = RlpError.WrongWidth(20, 0)

  private val ContractCreationName: Set[String] = Set("TransactionException.TYPE_3_TX_CONTRACT_CREATION")

  private val Undecodable = Table(
    ("case", "tool", "stated", "failure", "satisfied"),
    (
      "a header of no fork's shape, for the structures name",
      Ids,
      Set("BlockException.RLP_STRUCTURES_ENCODING"),
      HeaderShape,
      true
    ),
    (
      "a header of no fork's shape, for the format name",
      Ids,
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      HeaderShape,
      true
    ),
    (
      "a header field of the wrong width, for the format name, which holds any typed header failure",
      Ids,
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      DecodeFailure.Header(RlpError.WrongWidth(20, 0)),
      true
    ),
    (
      "a body of no block's shape, for the structures name",
      Ids,
      Set("BlockException.RLP_STRUCTURES_ENCODING"),
      BodyShape,
      true
    ),
    (
      "a body of no block's shape, for the format name",
      Ids,
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      BodyShape,
      false
    ),
    (
      "bytes that are not RLP, for the structures name",
      Ids,
      Set("BlockException.RLP_STRUCTURES_ENCODING"),
      NotRlp,
      false
    ),
    (
      "a header of no fork's shape, for a transaction's name",
      Ids,
      Set("TransactionException.TYPE_3_TX_WITH_FULL_BLOBS"),
      HeaderShape,
      false
    ),
    ("a header of no fork's shape, for a rule's name", Ids, Set("BlockException.INVALID_GASLIMIT"), HeaderShape, false),
    (
      "a header of no fork's shape, for the structures name, in retesteth's corpus",
      Retesteth,
      Set("BlockException.RLP_STRUCTURES_ENCODING"),
      HeaderShape,
      false
    ),
    (
      "a header of no fork's shape, for the format name, in testeth's corpus",
      Testeth,
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      HeaderShape,
      false
    ),
    (
      "a block stopped at a blob transaction's empty recipient, for the contract-creation name",
      Ids,
      ContractCreationName,
      StoppedAtBlobRecipient,
      true
    ),
    (
      "the same stop, for the structures name, which holds every typed failure",
      Ids,
      Set("BlockException.RLP_STRUCTURES_ENCODING"),
      StoppedAtBlobRecipient,
      true
    ),
    (
      "the same stop, for the name for a blob transaction carrying no blobs",
      Ids,
      Set("TransactionException.TYPE_3_TX_ZERO_BLOBS"),
      StoppedAtBlobRecipient,
      false
    ),
    (
      "the same stop, for the format name, whose header decodes",
      Ids,
      Set("BlockException.INCORRECT_BLOCK_FORMAT"),
      StoppedAtBlobRecipient,
      false
    ),
    (
      "the same stop, for the contract-creation name, in retesteth's corpus",
      Retesteth,
      ContractCreationName,
      StoppedAtBlobRecipient,
      false
    ),
    (
      "an empty address anywhere else in the body, for the contract-creation name",
      Ids,
      ContractCreationName,
      DecodeFailure.Block(EmptyAddress),
      false
    ),
    (
      "an empty address in a header, for the contract-creation name",
      Ids,
      ContractCreationName,
      DecodeFailure.Header(EmptyAddress),
      false
    )
  )

  /** A header in EIP-3675's form as retesteth classifies one: sixteen fields, a
    * zero difficulty, the empty ommers commitment and a zero nonce.
    */
  private val InTheForm: BlockHeader =
    BlockHeader(
      parentHash = A,
      ommersHash = BlockHeader.EmptyOmmersHash,
      beneficiary = EvmFixtures.address(0x0c),
      stateRoot = B,
      transactionsRoot = B,
      receiptsRoot = B,
      logsBloom = Bloom.Empty,
      difficulty = UInt256.Zero,
      number = UInt64.Zero,
      gasLimit = UInt64.Zero,
      gasUsed = UInt64.Zero,
      timestamp = UInt64.Zero,
      extraData = Bytes.Empty,
      seal = Seal.MixHashAndNonce(A, BlockNonce.Zero),
      tail = Some(BaseFeeTail(fee(7)))
    )

  /** The header the fault-only rows are asked over: one stating a difficulty,
    * so outside the form whatever else it states.
    */
  private val OutsideTheForm: BlockHeader = InTheForm.copy(difficulty = fee(10000))

  private val ZeroDifficulty: BlockFault = header(HeaderFault.DifficultyMismatch(UInt256.Zero, fee(131072)))

  private val TransitionName: Set[String] = Set("3675PoSBlockRejected")

  private val TransitionShaped = Table(
    ("case", "tool", "stated", "fault", "refused", "satisfied"),
    (
      "a zero difficulty over a header in the form, in retesteth's corpus",
      Retesteth,
      TransitionName,
      ZeroDifficulty,
      InTheForm,
      true
    ),
    ("the same refusal, in testeth's corpus", Testeth, TransitionName, ZeroDifficulty, InTheForm, false),
    ("the same refusal, in the generated tier", Ids, TransitionName, ZeroDifficulty, InTheForm, false),
    (
      "a zero difficulty over fifteen fields, which retesteth puts to the difficulty rule",
      Retesteth,
      TransitionName,
      ZeroDifficulty,
      InTheForm.copy(tail = None),
      false
    ),
    (
      "the same zero difficulty over fifteen fields, for retesteth's difficulty name",
      Retesteth,
      Set("InvalidDifficulty"),
      ZeroDifficulty,
      InTheForm.copy(tail = None),
      true
    ),
    (
      "a zero difficulty over a header whose ommers commitment is not the empty one",
      Retesteth,
      TransitionName,
      ZeroDifficulty,
      InTheForm.copy(ommersHash = B),
      false
    ),
    (
      "a zero difficulty over a header stating a nonce",
      Retesteth,
      TransitionName,
      ZeroDifficulty,
      InTheForm.copy(seal = Seal.MixHashAndNonce(A, NonZeroNonce)),
      false
    ),
    (
      "a non-zero difficulty refused, over a header otherwise in the form",
      Retesteth,
      TransitionName,
      Difficulty,
      InTheForm.copy(difficulty = fee(10000)),
      false
    ),
    ("a header in the form refused for its timestamp", Retesteth, TransitionName, Early, InTheForm, false),
    (
      "a header in the form refused for its state root",
      Retesteth,
      TransitionName,
      BlockFault.StateRootMismatch(A, B),
      InTheForm,
      false
    )
  )

  /** A blob transaction as a body carries one, well formed, whose access list
    * names one address.
    */
  private val CarriedBlob: Transaction =
    Transaction.Blob(
      chainId = UInt64.fromBits(1L),
      nonce = UInt64.Zero,
      maxPriorityFeePerGas = fee(1),
      maxFeePerGas = fee(7),
      gasLimit = UInt64.fromBits(21000L),
      recipient = EvmFixtures.address(0x0d),
      value = UInt256.Zero,
      data = Bytes.Empty,
      accessList = Vector(AccessTuple(EvmFixtures.address(0x0e), Vector(A))),
      maxFeePerBlobGas = fee(1),
      blobVersionedHashes = Vector(B),
      yParity = UInt256.Zero,
      r = fee(1),
      s = fee(1)
    )

  /** The format after the blob transaction's, whose recipient is an address too. */
  private val CarriedSetCode: Transaction =
    Transaction.SetCode(
      chainId = UInt64.fromBits(1L),
      nonce = UInt64.Zero,
      maxPriorityFeePerGas = fee(1),
      maxFeePerGas = fee(7),
      gasLimit = UInt64.fromBits(21000L),
      recipient = EvmFixtures.address(0x0d),
      value = UInt256.Zero,
      data = Bytes.Empty,
      accessList = Vector.empty,
      authorizationList = Vector.empty,
      yParity = UInt256.Zero,
      r = fee(1),
      s = fee(1)
    )

  private val CarriedLegacy: Transaction =
    Transaction.Legacy(
      nonce = UInt64.Zero,
      gasPrice = fee(7),
      gasLimit = UInt64.fromBits(21000L),
      to = Some(EvmFixtures.address(0x0d)),
      value = UInt256.Zero,
      data = Bytes.Empty,
      v = fee(27),
      r = fee(1),
      s = fee(1)
    )

  private val EmptyString: RlpItem = RlpItem.Bytes(IArray.empty[Byte])

  /** A recipient one byte short of an address. */
  private val ShortRecipient: RlpItem = RlpItem.Bytes(IArray.fill(19)(0x0d.toByte))

  /** `transaction` as a body element, with `change` applied to its typed
    * payload's fields.
    */
  private def withFields(transaction: Transaction)(change: Vector[RlpItem] => Vector[RlpItem]): RlpItem =
    RlpCodec[Transaction].encode(transaction) match
      case RlpItem.Bytes(typed) =>
        Rlp.decode(typed.drop(1)) match
          case Right(RlpItem.Sequence(fields)) =>
            RlpItem.Bytes(IArray(typed(0)) ++ Rlp.encode(RlpItem.Sequence(change(fields))))
          case other => fail("a typed payload that is not a sequence: " + other.toString)
      case other => fail("a typed transaction encoded as a list: " + other.toString)

  /** The payload position EIP-4844 and EIP-7702 both give the recipient. */
  private val RecipientField: Int = 5

  private def recipientAs(transaction: Transaction, recipient: RlpItem): RlpItem =
    withFields(transaction)(_.updated(RecipientField, recipient))

  /** A block of `header` whose body carries `transactions` and no ommers, as the
    * bytes a case would state.
    */
  private def carrying(header: RlpItem, transactions: RlpItem*): IArray[Byte] =
    Rlp.encode(
      RlpItem.Sequence(Vector(header, RlpItem.Sequence(transactions.toVector), RlpItem.Sequence(Vector.empty)))
    )

  private val FormHeader: RlpItem = RlpCodec[BlockHeader].encode(InTheForm)

  /** The blob transaction with its recipient, and an access list naming one
    * empty address, which reports the width an empty recipient does.
    */
  private def emptyAccessListAddress: RlpItem =
    withFields(CarriedBlob)(
      _.updated(8, RlpItem.Sequence(Vector(RlpItem.Sequence(Vector(EmptyString, RlpItem.Sequence(Vector.empty))))))
    )

  /** The same header with its beneficiary emptied, which no header decodes. */
  private def beneficiaryEmptied: RlpItem = FormHeader match
    case RlpItem.Sequence(fields) => RlpItem.Sequence(fields.updated(2, EmptyString))
    case other                    => fail("a header encoded as a string: " + other.toString)

  /** A `def` rather than a `val`, so a row whose bytes cannot be built fails its
    * property rather than aborting the suite.
    */
  private def stops = Table(
    ("block", "rlp", "stopped"),
    (
      "a blob transaction with an empty recipient",
      carrying(FormHeader, recipientAs(CarriedBlob, EmptyString)),
      Option(StoppedAtBlobRecipient)
    ),
    (
      "the same transaction, second after one that decodes",
      carrying(FormHeader, RlpCodec[Transaction].encode(CarriedLegacy), recipientAs(CarriedBlob, EmptyString)),
      Option(DecodeFailure.BlobRecipientEmpty(1))
    ),
    (
      "the same block with the recipient restored, which does not stop",
      carrying(FormHeader, RlpCodec[Transaction].encode(CarriedBlob)),
      Option.empty[DecodeFailure]
    ),
    (
      "a blob transaction whose recipient is one byte short",
      carrying(FormHeader, recipientAs(CarriedBlob, ShortRecipient)),
      Option(DecodeFailure.Block(RlpError.WrongWidth(20, 19)))
    ),
    (
      "a blob transaction with its recipient, and an empty address in its access list",
      carrying(FormHeader, emptyAccessListAddress),
      Option(DecodeFailure.Block(EmptyAddress))
    ),
    (
      "that transaction, before a blob transaction with an empty recipient",
      carrying(FormHeader, emptyAccessListAddress, recipientAs(CarriedBlob, EmptyString)),
      Option(DecodeFailure.Block(EmptyAddress))
    ),
    (
      "a set-code transaction with an empty recipient",
      carrying(FormHeader, recipientAs(CarriedSetCode, EmptyString)),
      Option(DecodeFailure.Block(EmptyAddress))
    ),
    (
      "a blob transaction with an empty recipient, behind a nonce written with a leading zero",
      carrying(
        FormHeader,
        withFields(CarriedBlob)(_.updated(RecipientField, EmptyString).updated(1, RlpItem.Bytes(IArray(0.toByte))))
      ),
      Option(DecodeFailure.Block(RlpError.NonCanonicalScalar))
    ),
    (
      "a blob transaction with an empty recipient, behind a transaction of no known format",
      carrying(FormHeader, RlpItem.Bytes(IArray(0x7e.toByte, 0xc0.toByte)), recipientAs(CarriedBlob, EmptyString)),
      Option(DecodeFailure.Block(RlpError.UnknownDiscriminant(0x7e)))
    ),
    (
      "a blob transaction with an empty recipient, under a header with an empty beneficiary",
      carrying(beneficiaryEmptied, recipientAs(CarriedBlob, EmptyString)),
      Option(DecodeFailure.Header(EmptyAddress))
    )
  )

  property("a refusal satisfies exactly the stated names that map to its rule, in its tool's names") {
    forAll(Rows) { (label, tool, stated, fault, satisfied) =>
      assert(
        BlockRefusalVocabulary.satisfies(ExpectedRejection(stated), fault, OutsideTheForm, tool) == satisfied,
        label + ": expected " + (if satisfied then "satisfied" else "refused") + " for " + fault.toString
      )
    }
  }

  property("retesteth's transition name is satisfied only by a difficulty refusal of a header in EIP-3675's form") {
    forAll(TransitionShaped) { (label, tool, stated, fault, refused, satisfied) =>
      assert(
        BlockRefusalVocabulary.satisfies(ExpectedRejection(stated), fault, refused, tool) == satisfied,
        label + ": expected " + (if satisfied then "satisfied" else "refused") + " for " + fault.toString +
          " over a header of " + refused.fieldCount.toString + " fields"
      )
    }
  }

  property("a block this build cannot decode satisfies exactly the stated names whose rule that is") {
    forAll(Undecodable) { (label, tool, stated, failure, satisfied) =>
      assert(
        BlockRefusalVocabulary.satisfiedByUndecodable(ExpectedRejection(stated), failure, tool) == satisfied,
        label + ": expected " + (if satisfied then "satisfied" else "refused") + " for " + failure.toString
      )
    }
  }

  property("a block stops at a blob transaction's empty recipient exactly where its decode stops there") {
    forAll(stops) { (label, rlp, expected) =>
      val stopped = RlpCodec.decodeFrom[Block](rlp).left.toOption.map(BlockRefusalVocabulary.failureOf(rlp, _))
      assert(
        stopped == expected,
        label + ": stopped " + stopped.toString + " where " + expected.toString + " was stated"
      )
    }
  }
