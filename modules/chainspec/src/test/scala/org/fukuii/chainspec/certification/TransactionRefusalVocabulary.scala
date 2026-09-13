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
    * It sits with the runner rather than with the reader because only this side
    * can name a [[org.fukuii.execution.Refusal]] at all: the reader is in a
    * module below the one that holds them, and a reader translating into a
    * vocabulary it cannot see would have to keep a second copy of it.
    */
  val byName: Map[String, Refusal] =
    Map(
      "TransactionException.TYPE_1_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_2_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_3_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.TYPE_4_TX_PRE_FORK" -> Refusal.TypeNotAdmitted,
      "TransactionException.INTRINSIC_GAS_TOO_LOW" -> Refusal.IntrinsicGasTooLow,
      "TransactionException.INITCODE_SIZE_EXCEEDED" -> Refusal.InitcodeTooLarge,
      "TransactionException.NONCE_IS_MAX" -> Refusal.NonceIsMax,
      "TransactionException.GAS_ALLOWANCE_EXCEEDED" -> Refusal.GasAllowanceExceeded,
      "TransactionException.NONCE_MISMATCH_TOO_LOW" -> Refusal.NonceMismatch,
      "TransactionException.NONCE_MISMATCH_TOO_HIGH" -> Refusal.NonceMismatch,
      "TransactionException.INSUFFICIENT_ACCOUNT_FUNDS" -> Refusal.InsufficientAccountFunds,
      "TransactionException.INVALID_SIGNATURE_VRS" -> Refusal.InvalidSignature,
      "TransactionException.SENDER_NOT_EOA" -> Refusal.SenderNotEoa,
      "TransactionException.WRONG_CHAIN_ID" -> Refusal.WrongChainId,
      // The name the corpus actually writes for the rule the entry above was
      // meant to reach: 68 files in the generated tier use this spelling and
      // none uses that one. Both are kept because a key that matches nothing
      // cannot produce a wrong verdict, and which of the two a future corpus
      // writes is the corpus's to decide -- but only this one is doing any work.
      "TransactionException.INVALID_CHAINID" -> Refusal.WrongChainId,
      // A PRE-EXISTING GAP, surfaced by the first corpus to carry a case for it
      // rather than introduced with one. The fee-market rule has been in this
      // build since London and no tier read before this one publishes a
      // transaction refused by it, so the name was never needed -- which is
      // what a corpus that could not disagree looks like from the vocabulary's
      // side.
      "TransactionException.INSUFFICIENT_MAX_FEE_PER_GAS" -> Refusal.FeeCapBelowBaseFee,
      // THE SECOND PRE-EXISTING GAP OF EXACTLY THAT SHAPE, and it is worth
      // stating that the entry above did not predict it. That one was surfaced
      // by a corpus carrying a transaction the fee market refuses for its cap;
      // this one is the market's OTHER refusal -- a tip above the cap -- and
      // the same reasoning would have found it had anyone applied it twice.
      //
      // It became reachable with the re-pinned legacy bulk and with nothing
      // else. Measured across the whole published state tier at this release:
      // five files anywhere state this name, four of them under forks this
      // build's ladder does not reach, and the fifth is
      // `for_cancun/ported_static/stEIP1559/test_tip_too_high`. So no corpus
      // registered before that one could have disagreed about it.
      "TransactionException.PRIORITY_GREATER_THAN_MAX_FEE_PER_GAS" -> Refusal.PriorityFeeAboveFeeCap,
      // The rules the blob format brings. Each name is the corpus's for one of
      // this document's own refusals, and the mapping is what turns a refusal
      // compared by NAME into a refusal compared by RULE -- without it a build
      // refusing for exactly the right reason is reported as diverging, which
      // is how these five arrived.
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
