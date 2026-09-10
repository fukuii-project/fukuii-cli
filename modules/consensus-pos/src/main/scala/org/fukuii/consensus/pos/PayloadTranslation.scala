package org.fukuii.consensus.pos

import org.fukuii.bytes.{Bytes, Hash, UInt256}
import org.fukuii.crypto.Keccak256
import org.fukuii.evm.BlockContext
import org.fukuii.execution.Withdrawals
import org.fukuii.rlp.RlpCodec
import org.fukuii.trie.Trie
import org.fukuii.types.{BaseFeeTail, BeaconRootTail, BlobGasTail, BlockHeader, BlockNonce, Seal, WithdrawalsTail}

/** Why a payload could not be turned into a header.
  *
  * ==Every one precedes execution, and none is an exception==
  *
  * The Engine API's answer to each is `{status: INVALID}` rather than a thrown
  * error, so each is a value the translation returns. That is the same shape as
  * [[ForkGateRefusal]] one layer down: this module answers the distinguishable
  * reason and the transport attaches the number.
  *
  * ==One case is RESERVED and is never returned==
  *
  * [[BlobVersionedHashesNotChecked]] names a check this build does not perform,
  * and it is here rather than in a comment because the register is what a
  * caller reads to learn what can go wrong — a gap recorded only beside the
  * field it concerns is invisible from the side that would have to handle it.
  * Its own note states what would make it live.
  */
enum TranslationRefusal:

  /** The header rebuilt from the payload does not hash to what the payload
    * claims.
    *
    * Both hashes are carried because the refusal is otherwise undiagnosable:
    * knowing only that they differ says nothing about which field was wrong, and
    * the pair is what a caller compares against the payload it sent.
    */
  case BlockHashMismatch(stated: Hash, derived: Hash)

  /** A transaction entry is empty.
    *
    * ==Checked here even though nothing here decodes a transaction==
    *
    * The specification makes it a standing obligation rather than a
    * fork-dependent one — *"Client software MUST validate that all
    * `transactions` have non-zero length (at least 1 byte). Client software MUST
    * run this validation in all cases even if this branch or any other branches
    * of the block tree are in an active sync process"*
    * (`ethereum/execution-apis` @ `6570b55` `src/engine/paris.md:164`) — and it
    * is the one transaction check that needs no decoder.
    *
    * Left out, an empty entry would still key a trie position and still produce
    * a root, so the payload would fail its block-hash check or pass it, and
    * either way the reported reason would be the wrong one.
    */
  case EmptyTransaction(index: Int)

  /** The call carries a parent beacon block root but the payload carries no
    * blob-gas fields.
    *
    * ==Unrepresentable rather than merely wrong==
    *
    * A header's tail is positional, so the beacon root sits behind the blob-gas
    * pair and there is no encoding for one without the other — see
    * [[org.fukuii.types.BlockHeader]], whose chain exists to exclude exactly
    * this. The combination belongs to no version of the method either, so a
    * consensus layer cannot produce it, and refusing is what keeps a header
    * this build cannot encode from being asked for.
    */
  case BeaconRootWithoutBlobGas

  /** The call carries execution requests, whose commitment this build does not
    * derive.
    *
    * ==Deferred deliberately, and the trigger is a fork rather than a
    * primitive==
    *
    * The commitment is a SHA-256 fold rather than a Keccak one — `sha256` over
    * each element, concatenated and hashed again, skipping any element of one
    * byte or fewer (`ethereum/go-ethereum` @ `02872e9ef`
    * `core/types/block.go:480-492`) — and this project already has that
    * primitive, so what is missing is not the ability to compute it.
    *
    * What is missing is a reason to trust the result. EIP-7685 activates at an
    * upgrade no schedule in this build reaches, so no network here can produce
    * a payload needing it, and the fixture range this module certifies against
    * carries none. A consensus commitment written now would be uncertified for
    * as long as that stayed true.
    *
    * **Refusing beats deriving nothing.** A header built without the field
    * would fail its block-hash check, and the caller would be told the hash
    * disagreed rather than that a commitment was missing — which is the failure
    * go-ethereum's own translation comments about the block access list at
    * `beacon/engine/types.go:276-280`: decode before the hash check, so that a
    * real defect is not reported as a hash mismatch.
    */
  case ExecutionRequestsNotDerived

  /** RESERVED, and returned by nothing today: the call carries expected blob
    * versioned hashes, which this build does not check.
    *
    * ==The one argument on this seam the block-hash check cannot back up==
    *
    * Every other field the caller supplies is either committed to by the header
    * this build derives, and so covered by comparing that header's hash against
    * the one the payload states, or refused outright. This one is not: the
    * versioned hashes are derived from the blob transactions INSIDE the
    * payload: the specification requires the actual array be obtained by
    * *"concatenating blob versioned hashes lists (`tx.blob_versioned_hashes`)
    * of each blob transaction included in the payload"* and `INVALID` returned
    * *"if the expected and the actual arrays don't match"*
    * (`ethereum/execution-apis` @ `6570b55` `src/engine/cancun.md:115-117`).
    * Nothing about that comparison moves a header field, so a payload whose
    * blob transactions disagree with the argument derives a header that hashes
    * correctly and passes every check this module makes.
    *
    * **It is a standing obligation, not a fork-conditional one.** The same
    * clause closes *"This validation MUST be instantly run in all cases even
    * during active sync process"* (`:119`) — the wording that makes
    * [[EmptyTransaction]] a check this module runs without a decoder. The
    * difference is that this one cannot be run without one.
    *
    * ==Why it is declared rather than returned==
    *
    * The check needs the payload's transactions DECODED, and this module
    * decodes none — the transactions root is taken over the encoded bytes for
    * the reason [[PayloadTranslation.headerOf]] records. So performing it here
    * would mean putting a decoder on a path built not to need one, and
    * refusing every blob-bearing payload instead would refuse payloads whose
    * headers this build derives correctly.
    *
    * **Neither is a translation-layer decision.** Whether an unperformable
    * check refuses the payload or is deferred to the layer that decodes the
    * transactions anyway is the verb's to settle, and the verb's own refusal
    * set is where a `-32602` for a wrong parameter set would live too. The case
    * is here so the gap is registered rather than resolved.
    *
    * ==What would make it live==
    *
    * The check being performed somewhere — which is the same trigger as
    * execution reaching blob transactions, since that is where the decoded
    * transactions exist. Until then this build must not be read as having
    * checked it.
    */
  case BlobVersionedHashesNotChecked

/** Turning what the consensus layer sends into what this client executes.
  *
  * ==This is the first production construction of a
  * [[org.fukuii.evm.BlockContext]], and that is why the phase exists==
  *
  * Two of that type's members are optional, and until now every value of it was
  * built by a test fixture that left both empty. The machine refuses rather than
  * defaults when it reads one that is missing — a zero randomness and a zero
  * base fee are both legal values, so standing either in would push a plausible
  * answer for a block that supplied none — but the refusal is reached only if
  * the operation that reads it actually runs.
  *
  * **So the hole was invisible rather than loud**: a post-merge block that never
  * executes `0x44` passes with `prevRandao` absent and nothing reports it.
  * Filling both here from the payload closes it at the only site that can, since
  * the payload is the first thing in this client that carries either value.
  */
object PayloadTranslation:

  /** The commitment a block with no ommers makes.
    *
    * Derived rather than written down. The value is a constant of the protocol
    * and appears in every post-merge header, but a 32-byte literal is a value
    * with no derivation attached, and this project's own encoder is what says
    * what an empty list of headers encodes to.
    */
  val EmptyOmmersHash: Hash = Keccak256.hash(RlpCodec.encodeTo(Seq.empty[BlockHeader]))

  /** What the machine reads about the block, taken off the payload.
    *
    * ==Both optional members are filled unconditionally, and neither can be
    * absent==
    *
    * `prevRandao` and `baseFeePerGas` are mandatory fields of every version of
    * the payload structure, including the first — see
    * [[ExecutionPayload.baseFeePerGas]] for why the base fee is mandatory here
    * where it is optional on a header. So a context built from a payload has
    * both, and the two `Option`s are `Some` by construction rather than by
    * luck.
    *
    * ==The difficulty is zero, and it is not read==
    *
    * EIP-3675 fixes it at zero for every post-merge block, and the operation
    * that would read it reports the randomness instead once the fork resolves
    * [[org.fukuii.evm.BlockRandomness.Eip4399]]. It is set because the header
    * commits to it, not because anything executes against it.
    * `ethereum/go-ethereum` @ `02872e9ef` sets the same constant in the same
    * place (`beacon/engine/types.go:365`, `Difficulty: common.Big0`).
    *
    * Total: every field it reads is mandatory on the payload, so there is
    * nothing here to refuse.
    */
  def contextOf(payload: ExecutionPayload): BlockContext =
    BlockContext(
      coinbase = payload.feeRecipient,
      number = payload.blockNumber.toBigInt,
      timestamp = payload.timestamp.toBigInt,
      difficulty = BigInt(0),
      gasLimit = payload.gasLimit.toBigInt,
      baseFee = Some(payload.baseFeePerGas.toBigInt),
      prevRandao = Some(payload.prevRandao)
    )

  /** The header the payload describes, with every commitment it omits derived.
    *
    * ==What the payload leaves out, and where each missing field comes from==
    *
    * Three fields are constants the merge fixed: the ommers hash is
    * [[EmptyOmmersHash]], the difficulty is zero, and the nonce is eight zero
    * bytes. Two are commitments derived here — the transactions root and the
    * withdrawals root. One arrives as a separate argument to the method rather
    * than inside the payload, the parent beacon block root. The seventh, the
    * requests hash, is refused: see
    * [[TranslationRefusal.ExecutionRequestsNotDerived]].
    *
    * ==The transactions root needs no transaction decoded==
    *
    * [[org.fukuii.trie.Trie.rootOfIndexed]] keys on the encoded form, and the
    * encoded form is exactly what the payload carries — a typed transaction's
    * envelope, which is the byte string a peer sent. So the commitment is over
    * the payload's own bytes and the decoder is not on this path.
    *
    * **`ethereum/go-ethereum` @ `02872e9ef` decodes first
    * (`beacon/engine/types.go:305`) and this does not**, which is worth being
    * precise about rather than glossing: it decodes because it is building a
    * block to execute, and its root is then taken over the re-encoded
    * transactions. The two agree on every canonically encoded input and the
    * decode is what rejects one that is not. **So this is the same commitment
    * and a weaker check**, and the check that closes the gap belongs with the
    * execution that needs the decoded transactions anyway.
    *
    * ==The seal is where the randomness goes==
    *
    * A post-merge header puts the previous randomness in the slot a
    * proof-of-work header fills with a mixed hash, and writes a zero nonce
    * beside it — which is [[org.fukuii.types.Seal.MixHashAndNonce]] exactly, and
    * why that case is named for its slots rather than for an engine.
    */
  def headerOf(request: NewPayloadRequest): Either[TranslationRefusal, BlockHeader] =
    val payload = request.payload
    for
      _ <- firstEmptyTransaction(payload.transactions)
      _ <- if request.executionRequests.isEmpty then Right(()) else Left(TranslationRefusal.ExecutionRequestsNotDerived)
      tail <- tailOf(request)
    yield BlockHeader(
      parentHash = payload.parentHash,
      ommersHash = EmptyOmmersHash,
      beneficiary = payload.feeRecipient,
      stateRoot = payload.stateRoot,
      transactionsRoot = Trie.rootOfIndexed(payload.transactions),
      receiptsRoot = payload.receiptsRoot,
      logsBloom = payload.logsBloom,
      difficulty = UInt256.Zero,
      number = payload.blockNumber,
      gasLimit = payload.gasLimit,
      gasUsed = payload.gasUsed,
      timestamp = payload.timestamp,
      extraData = payload.extraData,
      seal = Seal.MixHashAndNonce(payload.prevRandao, BlockNonce.Zero),
      tail = Some(tail)
    )

  /** The header, checked against the hash the payload states it has.
    *
    * ==The stated hash is a claim, and this is where it stops being one==
    *
    * Every field the header commits to came from the payload or was derived
    * from it, so a header that hashes to something else says the sender and
    * this client disagree about at least one of them. The specification makes
    * the check mandatory and unconditional — it MUST run *"in all cases even if
    * this branch or any other branches of the block tree are in an active sync
    * process"* (`ethereum/execution-apis` @ `6570b55` `src/engine/paris.md:166`).
    *
    * The refusal it earns changed at the second version:
    * `engine_newPayloadV1` answers `INVALID_BLOCK_HASH` and from
    * `engine_newPayloadV2` that status is *"supplanted by `INVALID`"*
    * (`src/engine/shanghai.md:105`). Which of the two a caller reports is the
    * verb's to decide from its own version, so this answers the fact rather
    * than the status.
    */
  def checkedHeaderOf(request: NewPayloadRequest): Either[TranslationRefusal, BlockHeader] =
    headerOf(request).flatMap { header =>
      if header.hash == request.payload.blockHash then Right(header)
      else Left(TranslationRefusal.BlockHashMismatch(request.payload.blockHash, header.hash))
    }

  private def firstEmptyTransaction(transactions: Seq[Bytes]): Either[TranslationRefusal, Unit] =
    transactions.zipWithIndex
      .collectFirst { case (bytes, index) if bytes.isEmpty => TranslationRefusal.EmptyTransaction(index) }
      .toLeft(())

  /** The header's tail, built to the depth the payload and the call reach.
    *
    * The base-fee link is unconditional because every payload carries a base
    * fee. Each link after it is present exactly when the payload's own appended
    * chain reaches that far, which is what keeps the two chains describing the
    * same block.
    */
  private def tailOf(request: NewPayloadRequest): Either[TranslationRefusal, BaseFeeTail] =
    val payload = request.payload
    payload.withdrawals match
      case None =>
        if request.parentBeaconBlockRoot.isEmpty then Right(BaseFeeTail(payload.baseFeePerGas))
        else Left(TranslationRefusal.BeaconRootWithoutBlobGas)
      case Some(withdrawals) =>
        blobGasTailOf(request).map { blobGas =>
          BaseFeeTail(payload.baseFeePerGas, Some(WithdrawalsTail(Withdrawals.root(withdrawals), blobGas)))
        }

  private def blobGasTailOf(request: NewPayloadRequest): Either[TranslationRefusal, Option[BlobGasTail]] =
    val payload = request.payload
    (payload.blobGasUsed, payload.excessBlobGas) match
      case (Some(used), Some(excess)) =>
        Right(Some(BlobGasTail(used, excess, request.parentBeaconBlockRoot.map(BeaconRootTail(_)))))
      case _ =>
        if request.parentBeaconBlockRoot.isEmpty then Right(None)
        else Left(TranslationRefusal.BeaconRootWithoutBlobGas)
