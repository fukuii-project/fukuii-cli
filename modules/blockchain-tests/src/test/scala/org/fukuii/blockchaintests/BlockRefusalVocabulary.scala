package org.fukuii.blockchaintests

import org.fukuii.chainspec.certification.TransactionRefusalVocabulary
import org.fukuii.consensus.{BlockFault, HeaderFault}
import org.fukuii.evm.fixtures.ExpectedRejection
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.fukuii.types.BlockHeader

/** Where a block this build cannot decode stopped decoding. */
enum DecodeFailure:

  /** Its header does not decode, read on its own. */
  case Header(error: RlpError)

  /** Its header decodes, or its bytes are no sequence to take one from, and the
    * block does not.
    */
  case Block(error: RlpError)

/** The corpus's names for a refused block, against the faults this build
  * refuses one for.
  *
  * ==A refusal is compared by name, and a name this does not hold matches
  * nothing==
  *
  * A block refused for some other rule than the one the case states is a
  * disagreement, even though both answers agree the block is invalid: the
  * reason is the only thing that separates a validator applying the rule the
  * case exercises from one refusing the block by accident. So a stated name
  * this vocabulary lacks is a divergence and never a skip.
  * `besu-eth/besu` @ `b330564a94` compares the same way and fails the same way,
  * where a key its mapping file lacks matches no message
  * (`ethereum/referencetests/.../BlockExceptionMatcher.java:127-137`).
  *
  * ==Names, per rule, rather than a message per stage==
  *
  * That file maps most header names onto one message, `"Header validation
  * failed"`, so any header rule satisfies any header name there. The faults
  * here carry one case per rule, so each name is held against the one rule the
  * ids' own definitions give it (`ethereum/execution-specs` @ `0cc100eb1`,
  * `packages/testing/src/execution_testing/exceptions/exceptions/block.py`),
  * and a header refused for its base fee does not satisfy a case stating its gas
  * limit.
  *
  * ==Two corpora, two spellings, one table==
  *
  * The generated tier names a rule `BlockException.<ID>`. `ethereum/legacytests`
  * names it without a prefix, in the vocabulary of the tool that filled it. What
  * a legacy name is for is read off the filler that states it: the header field
  * the case's filler overrides, under the name it expects for that network
  * (`ethereum/legacytests` @ `1f581b8cc`,
  * `src/LegacyTests/Cancun/BlockchainTestsFiller/InvalidBlocks/bcInvalidHeaderTest/`).
  *
  * ==Two halves, and only one of them lives here==
  *
  * A block refused because a transaction it carries is refused states the
  * transaction's name, and that name is
  * [[org.fukuii.chainspec.certification.TransactionRefusalVocabulary]]'s, which
  * the state tier reads too. The names for a rule over the block itself have
  * one reader, this tier, and sit beside it.
  *
  * ==An entry arrives with the first published case that states it==
  *
  * So every name below is exercised by a case this build certifies, and a name
  * mapped to the wrong rule is caught by that case rather than waiting for one.
  */
object BlockRefusalVocabulary:

  /** Whether `fault` is a refusal under any of the names `expected` states. */
  def satisfies(expected: ExpectedRejection, fault: BlockFault): Boolean =
    expected.stated.exists(name => refusesUnder(name, fault))

  /** Whether a block this build cannot decode is refused under a name
    * `expected` states.
    *
    * ==Only a name whose rule IS a decode refusal, and only for that kind of
    * failure==
    *
    * `BlockException.RLP_STRUCTURES_ENCODING` is defined as *"Block's rlp
    * encoding is valid but ethereum structures in it are invalid"*
    * (`block.py:113-116`), which is the second of the two groups
    * [[org.fukuii.rlp.RlpError]] separates: items that are RLP and are not the
    * value asked for. Bytes that are not RLP at all are a different rule, and
    * refusing them satisfies no name here.
    *
    * `BlockException.INCORRECT_BLOCK_FORMAT` is defined as *"Block's format is
    * incorrect, contains invalid fields, is missing fields, or contains fields
    * of a fork that is not active yet."* (`block.py:100-104`), and part of that
    * rule is decided here at decode. `org.fukuii.types.BlockHeader` is one type
    * across every fork, with each fork's fields on a chain of optional tails, so
    * a header whose length or layout no fork defines does not decode rather than
    * failing a check -- which is how the executable specification meets it too,
    * whose header type is per fork. **So it is satisfied only where the header
    * itself does not decode**: a header that reads and a body that does not is
    * some other rule's.
    *
    * **This holds it wider than the published cases show, and it is stated so.**
    * Any typed failure of the header decoded alone satisfies the name -- a wrong
    * arity, a non-canonical scalar, a field of the wrong width -- where the
    * published blocks stating it fail with the first two only. The definition's
    * *"contains invalid fields"* reads on each; a published case refusing one
    * kind under this name and another kind under a different name is the
    * trigger to narrow it.
    *
    * **The field reads this two ways, and this follows neither whole.**
    * `besu-eth/besu` @ `b330564a94` asserts a block that fails to decode is
    * invalid and compares no name (`BlockchainReferenceTestTools.java:239-241`),
    * so a decoder refusing a well-formed block would agree with every case it
    * met. `ethereum/execution-specs` @ `0cc100eb1` raises on the decode and
    * compares no name either (`tests/json_loader/helpers/load_blockchain_tests.py:187-190`).
    * Comparing the name is what keeps a decoder that refuses too much from
    * agreeing with a case about some other rule.
    */
  def satisfiedByUndecodable(expected: ExpectedRejection, failure: DecodeFailure): Boolean =
    failure match
      case DecodeFailure.Header(error) =>
        typed(error) && expected.stated.exists(name => decodeRules.contains(name) || name == IncorrectBlockFormat)
      case DecodeFailure.Block(error) => typed(error) && expected.stated.exists(decodeRules.contains)

  private val IncorrectBlockFormat: String = "BlockException.INCORRECT_BLOCK_FORMAT"

  /** The names `expected` states that no half of the vocabulary holds.
    *
    * Reported beside a divergence, because a case refused for the right rule
    * under a name this build has never mapped reads, without it, exactly like a
    * validator refusing for the wrong rule.
    */
  def unmapped(expected: ExpectedRejection): Set[String] =
    expected.stated.filterNot(name =>
      blockRules.contains(name) || decodeRules.contains(name) || TransactionRefusalVocabulary.byName.contains(name)
    )

  /** Where `rlp`, which does not decode as a block, stopped: at its header,
    * where its bytes are a sequence whose first item does not decode as one, and
    * at the block otherwise.
    */
  def failureOf(rlp: IArray[Byte], error: RlpError): DecodeFailure =
    Rlp.decode(rlp) match
      case Right(RlpItem.Sequence(items)) if items.nonEmpty =>
        RlpCodec[BlockHeader].decode(items(0)).fold(DecodeFailure.Header(_), _ => DecodeFailure.Block(error))
      case _ => DecodeFailure.Block(error)

  private def refusesUnder(name: String, fault: BlockFault): Boolean =
    blockRules.get(name).exists(rule => rule(fault)) ||
      TransactionRefusalVocabulary.byName.get(name).exists { reason =>
        fault match
          case BlockFault.TransactionRefused(rejection) => rejection.reason == reason
          case _                                        => false
      }

  /** Whether a decode failure is [[org.fukuii.rlp.RlpError]]'s typed group: the
    * bytes are RLP, and are not a block.
    *
    * Exhaustive, so an error added to either group is placed rather than
    * defaulted into one.
    */
  private def typed(error: RlpError): Boolean = error match
    case RlpError.ExpectedBytes | RlpError.ExpectedSequence | RlpError.NonCanonicalScalar        => true
    case RlpError.WrongWidth(_, _) | RlpError.WrongArity(_, _) | RlpError.UnknownDiscriminant(_) => true
    case RlpError.EmptyInput | RlpError.Truncated(_, _) | RlpError.NonCanonicalSingleByte(_)     => false
    case RlpError.NonOptimalLength(_) | RlpError.LeadingZeroInLength | RlpError.LengthTooLarge   => false
    case RlpError.TrailingBytes(_) | RlpError.NestingTooDeep(_)                                  => false

  /** The names whose rule is that a block's structure does not decode. */
  private val decodeRules: Set[String] = Set("BlockException.RLP_STRUCTURES_ENCODING")

  private def header(rule: PartialFunction[HeaderFault, Boolean]): BlockFault => Boolean = {
    case BlockFault.Header(fault) => rule.applyOrElse(fault, _ => false)
    case _                        => false
  }

  private def block(rule: PartialFunction[BlockFault, Boolean]): BlockFault => Boolean =
    fault => rule.applyOrElse(fault, _ => false)

  /** The three constants EIP-3675 fixes, which this build checks as one rule
    * and reports as three diagnoses -- `org.fukuii.chainspec.HeaderConstants`
    * states why.
    */
  private val eip3675Constants: BlockFault => Boolean = header {
    case HeaderFault.DifficultyNotFixed(_) => true
    case HeaderFault.NonceNotFixed(_)      => true
    case HeaderFault.OmmersNotEmpty(_)     => true
  }

  /** A rule over the block itself, by the name the corpus gives it.
    *
    * `INVALID_GASLIMIT` is defined as a gas limit not matching the limit formula
    * calculated from the parent, and this build's one rule for that is the
    * bound, floor included. `INVALID_BASEFEE_PER_GAS` is defined as a base fee
    * calculated incorrectly, which is the charge a parent requires -- not a
    * charge missing where a fee market requires one, which is a different rule.
    *
    * ==Two blob-gas rules this build decides at the header, held at both
    * positions==
    *
    * `org.fukuii.consensus.HeaderValidator` hoists two bounds on a header's
    * blob-gas spend, as go-ethereum and besu do, and states why each refuses
    * only blocks the specification refuses later. So a name for the later
    * position is also held at the earlier one where the rule is the same, and
    * the specification's own client mappers say which:
    *
    *   - `INCORRECT_BLOB_GAS_USED` is also a spend that is not a whole number of
    *     blobs, which no body's total can equal. Its reth mapper matches both
    *     *"blob gas used mismatch"* and *"blob gas used \d+ is not a multiple of
    *     blob gas per blob"* (`ethereum/execution-specs` @ `0cc100eb1`,
    *     `packages/testing/src/execution_testing/client_clis/clis/reth.py:92-95`).
    *   - `TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED`, a
    *     block's transactions spending more blob gas than it allows, is also the
    *     header's bound on that spend. Its go-ethereum mapper gives the
    *     transaction id and `BLOB_GAS_USED_ABOVE_LIMIT` one header message
    *     (`geth.py:133-138`), its reth mapper gives the transaction id the same
    *     one (`reth.py:73-75`), and `besu-eth/besu` @ `b330564a94` matches the
    *     transaction id to its header validation failure
    *     (`ethereum/referencetests/src/main/resources/block-exception-mapping.json:109`).
    *     The transaction half of the name stays with
    *     [[org.fukuii.chainspec.certification.TransactionRefusalVocabulary]].
    *
    * ==The legacy names==
    *
    * `InvalidGasLimit` and `InvalidGasLimit2` are two rules. The fillers state
    * the second for limits outside the parent's bound and the first for a limit
    * of `2^63` over a parent at `2^63 - 1`, which the bound admits and only the
    * maximum refuses (`ethereum/legacytests` @ `1f581b8cc`,
    * `src/LegacyTests/Cancun/BlockchainTestsFiller/InvalidBlocks/bcInvalidHeaderTest/GasLimitHigherThan2p63m1Filler.json`),
    * so each is held for its own rule and neither for the other's. The tool that
    * filled them maps the two the same way: `InvalidGasLimit` to its maximum's
    * message and `InvalidGasLimit2` to its step's (`ethereum/retesteth` @
    * `949f9b21`, `retesteth/configs/clientconfigs/t8ntool.cpp:268-269`, against
    * `retesteth/session/ToolBackend/Verification.cpp:315-316` and `:335-340`).
    *
    * **`InvalidGasLimit2` is held wider than that tool's rule.**
    * `HeaderFault.GasLimitOutOfBounds` also refuses a limit under the floor,
    * where the step the tool checks under that message has no floor. The fault is
    * one, and no published case separates the two halves; a published case
    * refusing one and not the other is the trigger to split it.
    *
    * `INVALID_GASLIMIT` stays with the bound: the generated tier states it for
    * no block above the maximum, and the go-ethereum and reth mappers match it
    * to their bound's messages and not to their maximum's (`geth.py:124`,
    * `reth.py:103-106`).
    *
    * `UnknownParent` and `UnknownParent2` are one rule under two names,
    * stated for two different wrong parent hashes. `3675PreParis1559BlockRejected`
    * is stated at the merge's fork for a block breaking EIP-3675's constants, and
    * the two names stated after it split that rule by constant.
    */
  private val blockRules: Map[String, BlockFault => Boolean] =
    Map(
      "BlockException.INVALID_GASLIMIT" -> header { case HeaderFault.GasLimitOutOfBounds(_, _) => true },
      "BlockException.INVALID_BASEFEE_PER_GAS" -> header { case HeaderFault.BaseFeeMismatch(_, _) => true },
      "BlockException.INVALID_WITHDRAWALS_ROOT" -> block { case BlockFault.WithdrawalsRootMismatch(_, _) => true },
      "BlockException.INCORRECT_EXCESS_BLOB_GAS" -> header { case HeaderFault.ExcessBlobGasMismatch(_, _) => true },
      "BlockException.INCORRECT_BLOB_GAS_USED" -> block {
        case BlockFault.BlobGasUsedMismatch(_, _)                       => true
        case BlockFault.Header(HeaderFault.BlobGasUsedNotWholeBlobs(_)) => true
      },
      "BlockException.BLOB_GAS_USED_ABOVE_LIMIT" -> header { case HeaderFault.BlobGasUsedAboveLimit(_, _) => true },
      "TransactionException.TYPE_3_TX_MAX_BLOB_GAS_ALLOWANCE_EXCEEDED" -> header {
        case HeaderFault.BlobGasUsedAboveLimit(_, _) => true
      },
      "BlockException.INCORRECT_BLOCK_FORMAT" -> header {
        case HeaderFault.BaseFeeMissing                     => true
        case HeaderFault.BaseFeeUnexpected(_)               => true
        case HeaderFault.WithdrawalsRootMissing             => true
        case HeaderFault.WithdrawalsRootUnexpected(_)       => true
        case HeaderFault.BlobGasMissing                     => true
        case HeaderFault.BlobGasUnexpected(_)               => true
        case HeaderFault.ParentBeaconBlockRootMissing       => true
        case HeaderFault.ParentBeaconBlockRootUnexpected(_) => true
      },
      "ExtraDataTooBig" -> header { case HeaderFault.ExtraDataAboveLimit(_, _) => true },
      "InvalidTimestampOlderParent" -> header { case HeaderFault.TimestampNotAfterParent(_, _) => true },
      "InvalidGasLimit" -> header { case HeaderFault.GasLimitAboveMaximum(_, _) => true },
      "InvalidGasLimit2" -> header { case HeaderFault.GasLimitOutOfBounds(_, _) => true },
      "InvalidNumber" -> header { case HeaderFault.NumberNotParentSuccessor(_, _) => true },
      "UnknownParent" -> block { case BlockFault.ParentHashMismatch(_, _) => true },
      "UnknownParent2" -> block { case BlockFault.ParentHashMismatch(_, _) => true },
      "InvalidTransactionsRoot" -> block { case BlockFault.TransactionsRootMismatch(_, _) => true },
      "InvalidGasUsed" -> block { case BlockFault.GasUsedMismatch(_, _) => true },
      "InvalidLogBloom" -> block { case BlockFault.LogsBloomMismatch(_, _) => true },
      "InvalidReceiptsStateRoot" -> block { case BlockFault.ReceiptsRootMismatch(_, _) => true },
      "InvalidStateRoot" -> block { case BlockFault.StateRootMismatch(_, _) => true },
      "3675PreParis1559BlockRejected" -> eip3675Constants,
      "PostParisDifficultyIsNot0" -> header { case HeaderFault.DifficultyNotFixed(_) => true },
      "PostParisUncleHashIsNotEmpty" -> header { case HeaderFault.OmmersNotEmpty(_) => true }
    )
