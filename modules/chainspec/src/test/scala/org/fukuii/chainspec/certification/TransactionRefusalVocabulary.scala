package org.fukuii.chainspec.certification

import org.fukuii.execution.Refusal

object TransactionRefusalVocabulary:

  /** The corpus's vocabulary for an invalid transaction, against the refusals
    * this build can produce.
    *
    * Only what admission decides appears here. A name absent from this map
    * survives verbatim in [[org.fukuii.evm.fixtures.ExpectedRejection.stated]],
    * so a case naming a rule this build has not implemented diverges and says
    * which one -- rather than passing because a refusal for some other reason
    * left the state root where the fixture expected it.
    *
    * It sits above the fixture reader rather than beside it because only a
    * module that sees [[org.fukuii.execution.Refusal]] can name one: the reader
    * is in a module below the one that holds them, and a reader translating
    * into a vocabulary it cannot see would have to keep a second copy of it. The
    * state tier and the block tier each read this one table.
    */
  val byName: Map[String, Refusal] =
    Map(
      "TransactionException.TYPE_1_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_2_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_3_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_4_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.INTRINSIC_GAS_TOO_LOW" -> Refusal.IntrinsicGasTooLow,
      // The floor's own name, kept apart from the charge above rather than
      // folded into it. The specification raises ONE error for both, comparing
      // the limit against the greater of the two; the corpus states the two in
      // SEPARATE cases, so a build producing one refusal for both would satisfy
      // each name with the other rule's refusal and the split would go unchecked.
      "TransactionException.INTRINSIC_GAS_BELOW_FLOOR_GAS_COST" -> Refusal.IntrinsicGasBelowFloor,
      "TransactionException.INITCODE_SIZE_EXCEEDED" -> Refusal.InitcodeTooLarge,
      "TransactionException.NONCE_IS_MAX" -> Refusal.NonceIsMax,
      "TransactionException.GAS_ALLOWANCE_EXCEEDED" -> Refusal.GasAllowanceExceeded,
      "TransactionException.NONCE_MISMATCH_TOO_LOW" -> Refusal.NonceTooLow,
      "TransactionException.NONCE_MISMATCH_TOO_HIGH" -> Refusal.NonceTooHigh,
      "TransactionException.INSUFFICIENT_ACCOUNT_FUNDS" -> Refusal.InsufficientAccountFunds,
      "TransactionException.INVALID_SIGNATURE_VRS" -> Refusal.InvalidSignature,
      "TransactionException.SENDER_NOT_EOA" -> Refusal.SenderNotEoa,
      "TransactionException.WRONG_CHAIN_ID" -> Refusal.WrongChainId,
      // The spelling the generated tier writes for the entry above's rule. Both
      // are kept: a key that matches nothing cannot produce a wrong verdict, and
      // which spelling a future corpus writes is the corpus's to decide.
      "TransactionException.INVALID_CHAINID" -> Refusal.WrongChainId,
      "TransactionException.INSUFFICIENT_MAX_FEE_PER_GAS" -> Refusal.FeeCapBelowBaseFee,
      "TransactionException.PRIORITY_GREATER_THAN_MAX_FEE_PER_GAS" -> Refusal.PriorityFeeAboveFeeCap,
      // The blob format's refusals, each under the corpus's name for it, so that a
      // refusal is compared by rule and not only by name.
      "TransactionException.TYPE_3_TX_CONTRACT_CREATION" -> Refusal.FormatMayNotDeploy,
      "TransactionException.TYPE_3_TX_ZERO_BLOBS" -> Refusal.BlobListEmpty,
      "TransactionException.TYPE_3_TX_INVALID_BLOB_VERSIONED_HASH" -> Refusal.BlobHashVersionUnknown,
      "TransactionException.INSUFFICIENT_MAX_FEE_PER_BLOB_GAS" -> Refusal.BlobFeeCapBelowCharge,
      // Two names for one rule, and the corpus states them as ALTERNATIVES on
      // the same case -- `A|B`, which the reader splits and this build satisfies
      // by producing a refusal either maps to. They are kept apart rather than
      // folded because a corpus is free to publish either alone, and a key that
      // matches nothing cannot produce a wrong verdict.
      "TransactionException.TYPE_3_TX_BLOB_COUNT_EXCEEDED" -> Refusal.BlobGasAllowanceExceeded,
      "TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED" -> Refusal.BlobGasAllowanceExceeded
    )
