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
  val byName: Map[String, Set[Refusal]] =
    Map(
      "TransactionException.TYPE_1_TX_PRE_FORK" -> Set(Refusal.TypeNotAdmitted),
      "TransactionException.TYPE_2_TX_PRE_FORK" -> Set(Refusal.TypeNotAdmitted),
      "TransactionException.TYPE_3_TX_PRE_FORK" -> Set(Refusal.TypeNotAdmitted),
      "TransactionException.TYPE_4_TX_PRE_FORK" -> Set(Refusal.TypeNotAdmitted),
      // THE GENERAL NAME COVERS BOTH HALVES, AND THE SPECIFIC ONE DOES NOT.
      // The asymmetry is the specification's: it raises ONE error for the whole
      // condition -- `max(intrinsic.regular, intrinsic.calldata_floor) > tx.gas`
      // (`ethereum/execution-specs` @ `0cc100eb1`
      // `forks/prague/transactions.py:574-577`) -- so nothing a network does
      // distinguishes the two, and a case saying only "the gas limit is too low"
      // is answered by whichever half this build found first.
      //
      // **This build's split is finer than the protocol's, which is a
      // diagnostic and must not become a disagreement.** Two refusals are kept
      // because which half fired is worth knowing and because the corpus does
      // state the specific name on cases authored for the floor; the general
      // name accepting either is what stops that finer answer reading as a
      // divergence.
      //
      // **A re-pinned legacy case is where the two come apart**, and it is why
      // one direction is not enough: a transaction authored against an earlier
      // fork's intrinsic charge states the general name, and at this fork the
      // FLOOR is what its limit falls under -- 21,009 against a floor of 21,010
      // where the ordinary charge is 21,004, so the ordinary charge alone would
      // have admitted it. The name was correct when it was written and is
      // correct now; only a reader drawing a distinction the protocol does not
      // would call that a disagreement.
      //
      // The specific name stays tight deliberately. It asserts WHICH half, this
      // build can answer that, and letting the ordinary charge satisfy it would
      // leave a wrong floor figure unchecked by every case authored to pin one.
      "TransactionException.INTRINSIC_GAS_TOO_LOW" ->
        Set(Refusal.IntrinsicGasTooLow, Refusal.IntrinsicGasBelowFloor),
      "TransactionException.INTRINSIC_GAS_BELOW_FLOOR_GAS_COST" -> Set(Refusal.IntrinsicGasBelowFloor),
      "TransactionException.INITCODE_SIZE_EXCEEDED" -> Set(Refusal.InitcodeTooLarge),
      "TransactionException.NONCE_IS_MAX" -> Set(Refusal.NonceIsMax),
      "TransactionException.GAS_ALLOWANCE_EXCEEDED" -> Set(Refusal.GasAllowanceExceeded),
      "TransactionException.NONCE_MISMATCH_TOO_LOW" -> Set(Refusal.NonceTooLow),
      "TransactionException.NONCE_MISMATCH_TOO_HIGH" -> Set(Refusal.NonceTooHigh),
      "TransactionException.INSUFFICIENT_ACCOUNT_FUNDS" -> Set(Refusal.InsufficientAccountFunds),
      "TransactionException.INVALID_SIGNATURE_VRS" -> Set(Refusal.InvalidSignature),
      "TransactionException.SENDER_NOT_EOA" -> Set(Refusal.SenderNotEoa),
      "TransactionException.WRONG_CHAIN_ID" -> Set(Refusal.WrongChainId),
      // The spelling the generated tier writes for the entry above's rule. Both
      // are kept: a key that matches nothing cannot produce a wrong verdict, and
      // which spelling a future corpus writes is the corpus's to decide.
      "TransactionException.INVALID_CHAINID" -> Set(Refusal.WrongChainId),
      "TransactionException.INSUFFICIENT_MAX_FEE_PER_GAS" -> Set(Refusal.FeeCapBelowBaseFee),
      "TransactionException.PRIORITY_GREATER_THAN_MAX_FEE_PER_GAS" -> Set(Refusal.PriorityFeeAboveFeeCap),
      // The blob format's refusals, each under the corpus's name for it, so that a
      // refusal is compared by rule and not only by name.
      "TransactionException.TYPE_3_TX_CONTRACT_CREATION" -> Set(Refusal.FormatMayNotDeploy),
      // The set-code format's own name for the same rule, and unreachable from a
      // decoded transaction for the same reason: that format types its recipient
      // as an address rather than an address-or-empty, here and in the
      // specification alike, so neither tree can decode one that deploys.
      "TransactionException.TYPE_4_TX_CONTRACT_CREATION" -> Set(Refusal.FormatMayNotDeploy),
      // A set-code transaction stating no authorization, which asks for nothing
      // the format exists to express.
      "TransactionException.TYPE_4_EMPTY_AUTHORIZATION_LIST" -> Set(Refusal.AuthorizationListEmpty),
      "TransactionException.TYPE_3_TX_ZERO_BLOBS" -> Set(Refusal.BlobListEmpty),
      "TransactionException.TYPE_3_TX_INVALID_BLOB_VERSIONED_HASH" -> Set(Refusal.BlobHashVersionUnknown),
      "TransactionException.INSUFFICIENT_MAX_FEE_PER_BLOB_GAS" -> Set(Refusal.BlobFeeCapBelowCharge),
      // Two names for one rule, and the corpus states them as ALTERNATIVES on
      // the same case -- `A|B`, which the reader splits and this build satisfies
      // by producing a refusal either maps to. They are kept apart rather than
      // folded because a corpus is free to publish either alone, and a key that
      // matches nothing cannot produce a wrong verdict.
      "TransactionException.TYPE_3_TX_BLOB_COUNT_EXCEEDED" -> Set(Refusal.BlobGasAllowanceExceeded),
      "TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED" -> Set(Refusal.BlobGasAllowanceExceeded)
    )
