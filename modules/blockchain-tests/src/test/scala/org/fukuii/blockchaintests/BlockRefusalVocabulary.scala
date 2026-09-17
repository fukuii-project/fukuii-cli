package org.fukuii.blockchaintests

import org.fukuii.bytes.{Address, UInt256}
import org.fukuii.chainspec.certification.TransactionRefusalVocabulary
import org.fukuii.consensus.{BlockFault, HeaderFault}
import org.fukuii.evm.fixtures.ExpectedRejection
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}
import org.fukuii.types.{BlockHeader, BlockNonce, Seal, Transaction, TransactionType}

/** Where a block this build cannot decode stopped decoding. */
enum DecodeFailure:

  /** Its header does not decode, read on its own. */
  case Header(error: RlpError)

  /** Its header decodes, and the first transaction its body does not decode is a
    * blob transaction whose recipient is empty, where decoding stopped.
    *
    * @param transaction
    *   that transaction's position in the body, counting from zero.
    */
  case BlobRecipientEmpty(transaction: Int)

  /** Its header decodes, or its bytes are no sequence to take one from, and the
    * block does not, stopping anywhere but where [[BlobRecipientEmpty]] stops.
    */
  case Block(error: RlpError)

/** The tool that filled a corpus, whose names its refusals are stated in.
  *
  * ==A name means the rule the tool that wrote it reported under it==
  *
  * The same string can name two rules in two corpora, because each tool maps
  * its names onto its own client's refusals. `InvalidGasLimit` is the worked
  * case: the `Constantinople` snapshot of `ethereum/legacytests` states it for
  * a limit above `2^63 - 1` and for one outside its parent's bound, and the
  * `Cancun` snapshot states it for the first alone and `InvalidGasLimit2` for
  * the second. Each case's own `_info` names what filled it.
  */
enum FillingTool:

  /** The generated tier's `BlockException` and `TransactionException` ids, which
    * its cases state as *"`execution-specs` generated test"*.
    */
  case ExecutionSpecs

  /** retesteth's names, as the `Cancun` snapshot states them. Its cases name
    * `retesteth-0.3.3-discontinued+commit.f30e58c2` as their filling tool, over
    * evmone's transition tool.
    */
  case Retesteth

  /** testeth's names, as the `Constantinople` snapshot states them: aleth's own
    * exception names. Its cases name `testeth 1.8.0-alpha.0-12+commit.b120a12c`
    * as what filled them, which predates retesteth's first definition of every
    * name that snapshot states -- the earliest is `ethereum/retesteth` @
    * `4959163f` (2019-12-16), against aleth's `b120a12c6` (2019-10-28).
    */
  case Testeth

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
  * tool that wrote it defines for it, and a header refused for its base fee does
  * not satisfy a case stating its gas limit.
  *
  * ==Three tools, and a table each==
  *
  * Each [[FillingTool]] has its own table, because a name's rule is the
  * definition of the tool that wrote it. The generated tier's ids are defined in
  * `ethereum/execution-specs` @ `0cc100eb1`
  * (`packages/testing/src/execution_testing/exceptions/exceptions/block.py`). A
  * legacy name is read off the filler that states it -- the header field its
  * case overrides, under the name it expects for that network -- and off the
  * tool's own definition of the name, cited per table. **Where two tools define
  * a name the same way, both tables hold it**, each on its own tool's evidence,
  * rather than one table standing for both.
  *
  * ==Two halves, and only one of them lives here==
  *
  * A block refused because a transaction it carries is refused states the
  * transaction's name, and that name is
  * [[org.fukuii.chainspec.certification.TransactionRefusalVocabulary]]'s, which
  * the state tier reads too. Those are the generated tier's ids alone. The names
  * for a rule over the block itself have one reader, this tier, and sit beside
  * it.
  *
  * ==An entry arrives with the first published case that states it==
  *
  * So every name below is exercised by a case this build certifies, and a name
  * mapped to a rule its case's block does not break is caught by that case.
  * **A rule held wider than its name is not**: the block still breaks it, so
  * the label keeps agreeing. `BlockRefusalVocabularyPropSpec`'s rows are what
  * refuse a widening toward the neighboring fault each row pairs a name with.
  */
object BlockRefusalVocabulary:

  /** Whether `fault`, refusing the block whose header is `refused`, is a refusal
    * under any of the names `expected` states, in the names `filledBy` writes.
    *
    * Every name but one is decided by the fault alone. The header is here for the
    * one whose rule depends on how its tool classifies the refused header --
    * `3675PoSBlockRejected`, whose reasons sit on retesteth's table below.
    */
  def satisfies(expected: ExpectedRejection, fault: BlockFault, refused: BlockHeader, filledBy: FillingTool): Boolean =
    expected.stated.exists(name => refusesUnder(name, fault, refused, filledBy))

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
    *
    * **Neither legacy tool writes a decode name**, so a block its corpus refuses
    * that this build cannot decode satisfies nothing there.
    *
    * ==One transaction's name is a decode rule too, for one shape alone==
    *
    * `TransactionException.TYPE_3_TX_CONTRACT_CREATION` is defined as
    * *"Transaction is a type 3 transaction and has an empty `to`."*
    * (`packages/testing/src/execution_testing/exceptions/exceptions/transaction.py:170-171`),
    * and EIP-4844 requires that field to *"always represent a 20-byte address"*
    * (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-4844.md:107`, Final). The
    * specification types it so (`src/ethereum/forks/cancun/transactions.py:304`)
    * and decodes a block's transactions as its body runs (`fork.py:652`), so its
    * own reading of a published block refuses that transaction at the decode,
    * before the check raising the name's own error (`fork.py:469-471`) is reached
    * -- which its transition tool reaches only from a transaction read as JSON
    * (`src/ethereum_spec_tools/loaders/transaction_loader.py:63-64`).
    *
    * **The field decides the rule at two positions, and the specification's own
    * client mappers hold both under this one name.** go-ethereum, erigon, reth and
    * ethrex type the recipient as an address, and the mappers give the name their
    * decode messages (`packages/testing/src/execution_testing/client_clis/clis/geth.py:83-86`,
    * `erigon.py:76-78`, `reth.py:28`, `ethrex.py:105-109`); besu and nethermind
    * read the empty field as no recipient and refuse at validation, and the
    * mappers give the name those messages (`besu.py:335-338`,
    * `nethermind.py:349-351`). So it is satisfied where a block stops decoding at
    * exactly that transaction, [[DecodeFailure.BlobRecipientEmpty]], and at no
    * other decode failure: a recipient of another width, an empty address
    * elsewhere, another format's recipient or a header that does not decode is
    * not this name's shape.
    */
  def satisfiedByUndecodable(expected: ExpectedRejection, failure: DecodeFailure, filledBy: FillingTool): Boolean =
    filledBy match
      case FillingTool.Retesteth | FillingTool.Testeth => false
      case FillingTool.ExecutionSpecs                  =>
        failure match
          case DecodeFailure.Header(error) =>
            typed(error) && expected.stated.exists(name => decodeRules.contains(name) || name == IncorrectBlockFormat)
          case DecodeFailure.BlobRecipientEmpty(_) =>
            expected.stated.exists(name => decodeRules.contains(name) || name == BlobContractCreation)
          case DecodeFailure.Block(error) => typed(error) && expected.stated.exists(decodeRules.contains)

  private val IncorrectBlockFormat: String = "BlockException.INCORRECT_BLOCK_FORMAT"

  private val BlobContractCreation: String = "TransactionException.TYPE_3_TX_CONTRACT_CREATION"

  /** The names `expected` states that no half of `filledBy`'s vocabulary holds.
    *
    * Reported beside a divergence, because a case refused for the right rule
    * under a name this build has never mapped reads, without it, exactly like a
    * validator refusing for the wrong rule.
    */
  def unmapped(expected: ExpectedRejection, filledBy: FillingTool): Set[String] =
    expected.stated.filterNot(name =>
      rulesOf(filledBy).contains(name) || decodeRulesOf(filledBy).contains(name) ||
        transactionNamesOf(filledBy).contains(name)
    )

  /** Where `rlp`, which does not decode as a block, stopped: at its header,
    * where its bytes are a sequence whose first item does not decode as one; at a
    * blob transaction's empty recipient, where [[stoppedAtBlobRecipient]] finds
    * the block stopped there; and at the block otherwise.
    */
  def failureOf(rlp: IArray[Byte], error: RlpError): DecodeFailure =
    Rlp.decode(rlp) match
      case Right(RlpItem.Sequence(items)) if items.nonEmpty =>
        RlpCodec[BlockHeader]
          .decode(items(0))
          .fold(
            DecodeFailure.Header(_),
            _ => stoppedAtBlobRecipient(items, error).getOrElse(DecodeFailure.Block(error))
          )
      case _ => DecodeFailure.Block(error)

  /** The position of the body's first transaction that does not decode, where it
    * is a blob transaction whose recipient is empty and the block stopped there.
    *
    * **Where the block stopped is read off its error as well as the shape.** A
    * body carrying such a transaction can stop decoding earlier -- at a field
    * before the recipient, or at a transaction before this one -- and an empty
    * address anywhere else reports the same width. The block's header has
    * decoded, and its body decodes its transactions before anything after them,
    * so the block's error is the first undecodable transaction's own; and no
    * field before a blob transaction's recipient is an address, so that error
    * is the empty recipient's width only where decoding reached the recipient.
    */
  private def stoppedAtBlobRecipient(items: Vector[RlpItem], error: RlpError): Option[DecodeFailure] =
    items.lift(1) match
      case Some(RlpItem.Sequence(transactions)) =>
        val first = transactions.indexWhere(RlpCodec[Transaction].decode(_).isLeft)
        Option.when(
          first >= 0 && error == EmptyRecipient && blobWithEmptyRecipient(transactions(first))
        )(DecodeFailure.BlobRecipientEmpty(first))
      case _ => None

  /** The error an address codec reports for an empty field. */
  private val EmptyRecipient: RlpError = RlpError.WrongWidth(Address.Width, 0)

  /** The recipient's position in a blob transaction's payload: `to` in EIP-4844's
    * `[chain_id, nonce, max_priority_fee_per_gas, max_fee_per_gas, gas_limit, to, ...]`
    * (`ethereum/EIPs` @ `d2a64c2d4`, `EIPS/eip-4844.md:102`).
    */
  private val BlobRecipientField: Int = 5

  /** Whether `item` is a blob transaction, as a body element carries one, whose
    * recipient field is the empty string.
    */
  private def blobWithEmptyRecipient(item: RlpItem): Boolean = item match
    case RlpItem.Bytes(payload) if payload.nonEmpty && (payload(0) & 0xff) == TransactionType.Blob.number =>
      Rlp.decode(payload.drop(1)) match
        case Right(RlpItem.Sequence(fields)) =>
          fields.lift(BlobRecipientField).exists {
            case RlpItem.Bytes(recipient) => recipient.isEmpty
            case RlpItem.Sequence(_)      => false
          }
        case _ => false
    case _ => false

  private def refusesUnder(name: String, fault: BlockFault, refused: BlockHeader, filledBy: FillingTool): Boolean =
    rulesOf(filledBy).get(name).exists(rule => rule(fault, refused)) ||
      transactionNamesOf(filledBy).get(name).exists { reason =>
        fault match
          case BlockFault.TransactionRefused(rejection) => rejection.reason == reason
          case _                                        => false
      }

  private def rulesOf(filledBy: FillingTool): Map[String, Rule] = filledBy match
    case FillingTool.ExecutionSpecs => executionSpecsRules
    case FillingTool.Retesteth      => retestethRules
    case FillingTool.Testeth        => testethRules

  private def decodeRulesOf(filledBy: FillingTool): Set[String] = filledBy match
    case FillingTool.ExecutionSpecs                  => decodeRules
    case FillingTool.Retesteth | FillingTool.Testeth => Set.empty

  private def transactionNamesOf(filledBy: FillingTool) = filledBy match
    case FillingTool.ExecutionSpecs                  => TransactionRefusalVocabulary.byName
    case FillingTool.Retesteth | FillingTool.Testeth => Map.empty

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

  /** A name's rule: whether a refusal for the fault, of the block whose header
    * is given, is one the name states.
    */
  private type Rule = (BlockFault, BlockHeader) => Boolean

  private def header(rule: PartialFunction[HeaderFault, Boolean]): Rule =
    (fault, _) =>
      fault match
        case BlockFault.Header(inner) => rule.applyOrElse(inner, _ => false)
        case _                        => false

  private def block(rule: PartialFunction[BlockFault, Boolean]): Rule =
    (fault, _) => rule.applyOrElse(fault, _ => false)

  /** The three constants EIP-3675 fixes, which this build checks as one rule
    * and reports as three diagnoses -- `org.fukuii.chainspec.HeaderConstants`
    * states why.
    */
  private val eip3675Constants: Rule = header {
    case HeaderFault.DifficultyNotFixed(_) => true
    case HeaderFault.NonceNotFixed(_)      => true
    case HeaderFault.OmmersNotEmpty(_)     => true
  }

  /** A header refused for its difficulty where it is in EIP-3675's form, before
    * any transition -- retesteth's table below states why that is the whole of
    * `3675PoSBlockRejected` on every chain this build runs.
    */
  private val eip3675FormBeforeTransition: Rule = (fault, refused) =>
    fault match
      case BlockFault.Header(HeaderFault.DifficultyMismatch(_, _)) => inEip3675Form(refused)
      case _                                                       => false

  /** The fields of a header retesteth can read in EIP-3675's form: the fifteen
    * every header carries, and the fee market's base fee.
    */
  private val Eip3675FormFields: Int = BlockHeader.MandatoryFields + 1

  /** Whether retesteth reads `refused` as a header in EIP-3675's form: sixteen
    * fields, a zero difficulty, the empty ommers commitment and a zero nonce, the
    * four conditions of `isHeaderParis`
    * (`retesteth/testStructures/types/Ethereum/Blocks/BlockHeaderReader.cpp:85-107`).
    *
    * The count is one of the four: a header of fifteen fields stating the three
    * constants is one retesteth reads as a header before the fee market and puts
    * to the difficulty rule.
    */
  private def inEip3675Form(refused: BlockHeader): Boolean =
    val zeroNonce = refused.seal match
      case Seal.MixHashAndNonce(_, nonce) => nonce == BlockNonce.Zero
      case Seal.AuthorityRound(_, _)      => false
    refused.fieldCount == Eip3675FormFields && refused.difficulty == UInt256.Zero &&
    refused.ommersHash == BlockHeader.EmptyOmmersHash && zeroNonce

  /** The generated tier's ids, each held for the rule its definition gives it.
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
    * `INVALID_GASLIMIT` stays with the bound: the generated tier states it for
    * no block above the maximum, and the go-ethereum and reth mappers match it
    * to their bound's messages and not to their maximum's (`geth.py:124`,
    * `reth.py:103-106`).
    */
  private val executionSpecsRules: Map[String, Rule] =
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
      }
    )

  /** retesteth's names, each held for the rule retesteth maps it to.
    *
    * ==Where each is defined==
    *
    * `ethereum/retesteth` @ `f30e58c2`, the commit the `Cancun` snapshot's cases
    * name as their filling tool, maps each name to the message its transition
    * tool's configuration expects (`retesteth/configs/clientconfigs/evmone.cpp`),
    * and emits the header messages itself while importing a block:
    *
    *   - `InvalidGasLimit` to *"Header gasLimit > 0x7fffffffffffffff"* (`:201`),
    *     raised against the maximum alone
    *     (`retesteth/session/ToolBackend/Verification.cpp:314-316`);
    *   - `InvalidGasLimit2` to *"Invalid gaslimit:"* (`:202`), raised against
    *     the parent's bound (`Verification.cpp:335-340`);
    *   - `InvalidDifficulty` to *"Invalid difficulty:"* (`:197`), raised where
    *     the header's difficulty is not the formula's for its parent and where
    *     it is below the minimum (`Verification.cpp:19-29,44-48`);
    *   - `InvalidTimestampOlderParent` (`:226`, `Verification.cpp:12-17`),
    *     `InvalidNumber` (`:224`, `Verification.cpp:326-328`) and
    *     `ExtraDataTooBig` (`:195`, `Verification.cpp:306-307`), each a header
    *     rule of that name;
    *   - `UnknownParent` and `UnknownParent2` to one message, *"unknown parent
    *     hash"* (`:233-234`), stated for two different wrong parent hashes;
    *   - `InvalidStateRoot`, `InvalidReceiptsStateRoot`, `InvalidTransactionsRoot`,
    *     `InvalidLogBloom`, `InvalidGasUsed` and `InvalidUnclesHash` to *"Error
    *     in field: "* and the field (`:227-237`), raised where the header a block
    *     states differs from the one its transition tool built from the body
    *     (`retesteth/session/ToolBackend/ToolChain.cpp:408-416`), the ommers
    *     commitment recomputed from the body's own ommers (`:388`).
    *
    * **`InvalidGasLimit2` is held wider than that tool's rule.**
    * `HeaderFault.GasLimitOutOfBounds` also refuses a limit under the floor,
    * where the step the tool checks under that message has no floor. The fault is
    * one, and no published case separates the two halves; a published case
    * refusing one and not the other is the trigger to split it.
    *
    * `3675PreParis1559BlockRejected` is stated at the merge's fork for a block
    * breaking EIP-3675's constants, and the two names stated after it split that
    * rule by constant.
    *
    * ==`3675PoSBlockRejected` is held against the refused header as well as the
    * fault==
    *
    * retesteth maps it to *"Parent (transition) block has not reached TTD"*
    * (`:386`). It reads a header in EIP-3675's form -- sixteen fields, a zero
    * difficulty, the empty ommers commitment and a zero nonce
    * (`retesteth/testStructures/types/Ethereum/Blocks/BlockHeaderReader.cpp:85-107`)
    * -- and, after the rules every header meets against its parent, puts such a
    * header to a terminal-total-difficulty check where any other goes to the
    * difficulty rule (`Verification.cpp:360-370`). That check reads a chain
    * defining no terminal total difficulty as `0xffffffffffffffffffffffffffff`
    * (`Verification.cpp:273-295`, the default at `:290`); retesteth's London
    * configuration defines none (`retesteth/configs/genesis/default/London.cpp`),
    * and no network this build runs models one, since a schedule resolves the
    * transition at a height. **So on every chain here the check cannot pass, and
    * the name reduces to a header in that form refused before the transition**,
    * which is [[org.fukuii.consensus.HeaderFault.DifficultyMismatch]] over that
    * header: the form states a zero difficulty, and the mechanism before the
    * transition requires one at least its floor.
    *
    * That is the rule under the case's own network definition rather than a
    * family around it, and it is why this one name reads the header. A zero
    * difficulty outside the form -- fifteen fields, or a nonce or ommers
    * commitment the form does not state -- is one retesteth puts to the
    * difficulty rule as `InvalidDifficulty`, and does not satisfy this name.
    * `InvalidDifficulty` is not narrowed the other way, because retesteth never
    * puts a header in the form to the difficulty rule, so no case it fills can
    * state that name for one.
    *
    * The field refuses the same block and differs only in the reason:
    * `ethereum/execution-specs` @ `0cc100eb1` for its difficulty
    * (`src/ethereum/forks/london/fork.py:354-362`), and `ethereum/go-ethereum-pow`
    * @ `v1.10.26`'s import, which wraps ethash in its beacon engine
    * (`eth/ethconfig/config.go:248`), as an invalid terminal block
    * (`consensus/beacon/consensus.go:102-130`).
    *
    * **The trigger to replace it:** a network with a defined terminal total
    * difficulty is built, and this name then maps to that transition rule's fault.
    */
  private val retestethRules: Map[String, Rule] =
    Map(
      "ExtraDataTooBig" -> header { case HeaderFault.ExtraDataAboveLimit(_, _) => true },
      "InvalidDifficulty" -> header { case HeaderFault.DifficultyMismatch(_, _) => true },
      "InvalidTimestampOlderParent" -> header { case HeaderFault.TimestampNotAfterParent(_, _) => true },
      "InvalidGasLimit" -> header { case HeaderFault.GasLimitAboveMaximum(_, _) => true },
      "InvalidGasLimit2" -> header { case HeaderFault.GasLimitOutOfBounds(_, _) => true },
      "InvalidNumber" -> header { case HeaderFault.NumberNotParentSuccessor(_, _) => true },
      "UnknownParent" -> block { case BlockFault.ParentHashMismatch(_, _) => true },
      "UnknownParent2" -> block { case BlockFault.ParentHashMismatch(_, _) => true },
      "InvalidUnclesHash" -> block { case BlockFault.OmmersHashMismatch(_, _) => true },
      "InvalidTransactionsRoot" -> block { case BlockFault.TransactionsRootMismatch(_, _) => true },
      "InvalidGasUsed" -> block { case BlockFault.GasUsedMismatch(_, _) => true },
      "InvalidLogBloom" -> block { case BlockFault.LogsBloomMismatch(_, _) => true },
      "InvalidReceiptsStateRoot" -> block { case BlockFault.ReceiptsRootMismatch(_, _) => true },
      "InvalidStateRoot" -> block { case BlockFault.StateRootMismatch(_, _) => true },
      "3675PreParis1559BlockRejected" -> eip3675Constants,
      "3675PoSBlockRejected" -> eip3675FormBeforeTransition,
      "PostParisDifficultyIsNot0" -> header { case HeaderFault.DifficultyNotFixed(_) => true },
      "PostParisUncleHashIsNotEmpty" -> header { case HeaderFault.OmmersNotEmpty(_) => true }
    )

  /** testeth's names, each held for the aleth refusal it matches.
    *
    * ==Where each is defined==
    *
    * testeth compares a stated name with the exception aleth raises while
    * importing the block, requiring the exception's description to contain it
    * (`ethereum/aleth` @ `b120a12c6`,
    * `test/tools/jsontests/BlockChainTests.cpp:947-961`). So each name is the
    * aleth exception of that name, held for the rule aleth raises it for at the
    * sites below. **aleth raises some of them elsewhere too**, and this table
    * does not hold those readings -- among them `InvalidNumber` for a number too
    * large (`libethcore/BlockHeader.cpp:196-197`), `UnknownParent` for an ommer's
    * parent (`libethereum/Block.cpp:532-533`) and `InvalidStateRoot` for a stored
    * block without its state (`:216-218`). So the readings here are narrower than
    * aleth's, and a case refused at one of those sites would not agree:
    *
    *   - `InvalidGasLimit` is **one** exception for three comparisons: a limit
    *     under the floor or above `0x7fffffffffffffff`, and a limit outside the
    *     parent's bound (`libethcore/SealEngine.cpp:74-80,100-114`, with the
    *     floor and the maximum at `libethcore/ChainOperationParams.cpp:27-28`).
    *     This build refuses those as two faults, so the name holds both --
    *     that is aleth's rule, and not a family around it;
    *   - `TooMuchGasUsed`, gas used above the header's own limit
    *     (`libethcore/BlockHeader.cpp:199-200`);
    *   - `InvalidTimestamp`, a timestamp no later than the parent's (`:207-208`),
    *     and `InvalidNumber`, a number not the parent's successor (`:210-211`);
    *   - `InvalidTransactionsRoot` and `InvalidUnclesHash`, each commitment over
    *     the body against the body (`:222-251`);
    *   - `InvalidDifficulty`, a difficulty other than the formula's for its
    *     parent under the no-proof seal engine these cases name, and one below
    *     the minimum (`libethcore/SealEngine.cpp:50-54,69-72`);
    *   - `ExtraDataTooBig`, extra data above the bound (`:82-88`);
    *   - `UnknownParent`, a parent the chain does not hold
    *     (`libethereum/BlockChain.cpp:542-546`);
    *   - `InvalidReceiptsStateRoot`, `InvalidLogBloom`, `InvalidStateRoot` and
    *     `InvalidGasUsed`, each commitment against what running the block
    *     produced (`libethereum/Block.cpp:476-491,599-610`).
    *
    * The name retesteth later gave the bound alone, `InvalidGasLimit2`, first
    * appears in `ethereum/retesteth` @ `5f82b44c` (2019-12-19), after the aleth
    * commit these cases were filled with, so no case here states it.
    */
  private val testethRules: Map[String, Rule] =
    Map(
      "ExtraDataTooBig" -> header { case HeaderFault.ExtraDataAboveLimit(_, _) => true },
      "InvalidDifficulty" -> header { case HeaderFault.DifficultyMismatch(_, _) => true },
      "InvalidGasLimit" -> header {
        case HeaderFault.GasLimitAboveMaximum(_, _) => true
        case HeaderFault.GasLimitOutOfBounds(_, _)  => true
      },
      "TooMuchGasUsed" -> header { case HeaderFault.GasUsedAboveLimit(_, _) => true },
      "InvalidTimestamp" -> header { case HeaderFault.TimestampNotAfterParent(_, _) => true },
      "InvalidNumber" -> header { case HeaderFault.NumberNotParentSuccessor(_, _) => true },
      "UnknownParent" -> block { case BlockFault.ParentHashMismatch(_, _) => true },
      "InvalidUnclesHash" -> block { case BlockFault.OmmersHashMismatch(_, _) => true },
      "InvalidTransactionsRoot" -> block { case BlockFault.TransactionsRootMismatch(_, _) => true },
      "InvalidGasUsed" -> block { case BlockFault.GasUsedMismatch(_, _) => true },
      "InvalidLogBloom" -> block { case BlockFault.LogsBloomMismatch(_, _) => true },
      "InvalidReceiptsStateRoot" -> block { case BlockFault.ReceiptsRootMismatch(_, _) => true },
      "InvalidStateRoot" -> block { case BlockFault.StateRootMismatch(_, _) => true }
    )
