package org.fukuii.blockchaintests

import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.consensus.{BlockFault, HeaderFault}
import org.fukuii.evm.EvmFixtures
import org.fukuii.evm.fixtures.ExpectedRejection
import org.fukuii.execution.{BlockRejection, Refusal}
import org.fukuii.rlp.RlpError
import org.fukuii.types.{BaseFeeTail, BlockHeader, BlockNonce, Bloom, Seal}

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
    BlockFault.TransactionRefused(BlockRejection(index = 0, reason = reason, unbuilt = None))

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
