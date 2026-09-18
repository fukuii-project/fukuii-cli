package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, Hash, UInt256, UInt64}
import org.fukuii.execution.ExecutionRequests
import org.fukuii.rlp.{RlpCodec, RlpItem}
import org.fukuii.types.{BlockHeader, Seal, Transaction}

/** The two holes this phase exists to fill, and the refusals around them.
  *
  * The published vectors in [[PayloadTranslationPropSpec]] settle whether the
  * derivation is right; this settles whether the values the machine reads are
  * carried, which those vectors cannot see because both of them state a
  * randomness of zero.
  */
class PayloadTranslationSpec extends AnyFlatSpec:

  private val paris = PublishedPayloads.paris
  private val shanghai = PublishedPayloads.shanghai
  private val cancunish = PosFixtures.withBlobGas

  /** A Cancun-shaped payload with a beacon root and `requests` as the fourth
    * argument, translated.
    */
  private def withRequests(requests: Seq[Bytes]): Either[TranslationRefusal, BlockHeader] =
    PayloadTranslation.headerOf(
      NewPayloadRequest(
        cancunish,
        Some(BlobAndBeaconArguments(Seq.empty, beaconRoot, Some(ExecutionRequestsArgument(requests))))
      )
    )
  private val beaconRoot = PosFixtures.hash(0xbe)

  private def requestWithBeaconRoot(payload: ExecutionPayload): NewPayloadRequest =
    NewPayloadRequest(payload, Some(BlobAndBeaconArguments(Seq.empty, beaconRoot)))

  private def sealOf(payload: ExecutionPayload): Option[Seal] =
    PayloadTranslation.headerOf(NewPayloadRequest(payload)).toOption.map(_.seal)

  private val firstHash = PosFixtures.hash(0xa1)
  private val secondHash = PosFixtures.hash(0xa2)

  /** One blob transaction, in the canonical envelope a payload carries.
    *
    * Built through this project's own encoder rather than as a byte literal, so
    * the bytes the check reads are the bytes the codec writes and a test cannot
    * pass by agreeing with a hand-typed envelope nothing else produces.
    */
  private def blobTransaction(hashes: Seq[Hash]): Bytes =
    val transaction = Transaction.Blob(
      chainId = UInt64.fromBits(1L),
      nonce = UInt64.fromBits(0L),
      maxPriorityFeePerGas = UInt256.Zero,
      maxFeePerGas = UInt256.Zero,
      gasLimit = UInt64.fromBits(21000L),
      recipient = PosFixtures.address(0x77),
      value = UInt256.Zero,
      data = Bytes.Empty,
      accessList = Seq.empty,
      maxFeePerBlobGas = UInt256.Zero,
      blobVersionedHashes = hashes,
      yParity = UInt256.Zero,
      r = UInt256.Zero,
      s = UInt256.Zero
    )
    RlpCodec[Transaction].encode(transaction) match
      case RlpItem.Bytes(payload) => Bytes.fromIArray(payload)
      case _: RlpItem.Sequence    => fail("a typed transaction encodes to an opaque byte string, not a list")

  private def requestCarrying(transactions: Seq[Bytes], expected: Seq[Hash]): NewPayloadRequest =
    NewPayloadRequest(
      cancunish.copy(transactions = transactions),
      Some(BlobAndBeaconArguments(expected, beaconRoot))
    )

  "PayloadTranslation.contextOf" should "carry the payload's randomness rather than leaving it absent" in
    assert(
      PayloadTranslation.contextOf(paris).prevRandao.contains(paris.prevRandao),
      "the machine refuses to invent a randomness, and this is the first site in the client that can supply one"
    )

  it should "carry the payload's base fee rather than leaving it absent" in
    assert(
      PayloadTranslation.contextOf(paris).baseFee.contains(paris.baseFeePerGas.toBigInt),
      "the base fee has the same shape as the randomness and the same site is the only one that can fill it"
    )

  it should "fill the randomness even where the payload's value is zero" in
    assert(
      PayloadTranslation.contextOf(paris).prevRandao.isDefined,
      "a zero randomness is a legal value, so present-and-zero must not collapse into absent"
    )

  it should "report a difficulty of zero" in
    assert(
      PayloadTranslation.contextOf(paris).difficulty == BigInt(0),
      "the merge fixed it, and the header commits to it even though the operation that would read it does not"
    )

  it should "carry the fee recipient as the account credited" in
    assert(
      PayloadTranslation.contextOf(paris).coinbase == paris.feeRecipient,
      "the payload names who is credited, and no header field redirects it on this family"
    )

  it should "carry the block number, timestamp and gas limit off the payload" in
    assert(
      PayloadTranslation.contextOf(paris) == PayloadTranslation
        .contextOf(paris)
        .copy(
          number = paris.blockNumber.toBigInt,
          timestamp = paris.timestamp.toBigInt,
          gasLimit = paris.gasLimit.toBigInt
        ),
      "the three quantities are read straight off the payload and none is derived"
    )

  "PayloadTranslation.headerOf" should "put the payload's randomness in the seal's first slot" in
    assert(
      sealOf(paris).contains(Seal.MixHashAndNonce(paris.prevRandao, org.fukuii.types.BlockNonce.Zero)),
      "a post-merge header carries the randomness where a mined one carries a mixed hash, with a zero nonce beside it"
    )

  it should "carry a randomness that is not zero through to the seal" in {
    val loud = paris.copy(prevRandao = PosFixtures.hash(0x5a))
    assert(
      sealOf(loud).contains(Seal.MixHashAndNonce(PosFixtures.hash(0x5a), org.fukuii.types.BlockNonce.Zero)),
      "both published vectors state a zero randomness, so this is what shows the field is carried at all"
    )
  }

  it should "commit to the empty ommers hash" in
    assert(
      PayloadTranslation.headerOf(NewPayloadRequest(paris)).map(_.ommersHash) ==
        Right(PayloadTranslation.EmptyOmmersHash),
      "no post-merge block has ommers, and the commitment is derived from this project's own encoder"
    )

  it should "derive a withdrawals root where the payload carries a list" in
    assert(
      PayloadTranslation.headerOf(NewPayloadRequest(shanghai)).map(_.withdrawalsRoot.isDefined) == Right(true),
      "the payload carries the list and the header carries only its commitment, so the root is derived here"
    )

  it should "carry no withdrawals root where the payload carries no list" in
    assert(
      PayloadTranslation.headerOf(NewPayloadRequest(paris)).map(_.withdrawalsRoot) == Right(None),
      "absent and empty are different facts, and a payload from before the proposal has neither field nor root"
    )

  it should "carry the parent beacon root supplied beside the payload" in
    assert(
      PayloadTranslation.headerOf(requestWithBeaconRoot(cancunish)).map(_.parentBeaconBlockRoot) ==
        Right(Some(beaconRoot)),
      "the value travels as a method argument rather than inside the payload, and the header is where it lands"
    )

  it should "refuse a parent beacon root where the payload carries no blob-gas fields" in
    assert(
      PayloadTranslation.headerOf(requestWithBeaconRoot(shanghai)) ==
        Left(TranslationRefusal.BeaconRootWithoutBlobGas),
      "a header's tail is positional and has no encoding for the later field without the earlier one"
    )

  it should "derive the requests commitment over a list the call carried" in
    assert(
      withRequests(Seq(PosFixtures.requestBytes)).map(_.requestsHash) ==
        Right(Some(ExecutionRequests.hashOf(Vector(PosFixtures.requestBytes)))),
      "the header's seventh derived field is the commitment over the list the consensus layer handed in"
    )

  it should "state a commitment over an empty list, and none where the call carried no list" in
    // Absent and empty are different header values. A call below the version
    // that added the argument states none; a call carrying `[]` states the hash
    // of nothing, and a build collapsing them would encode a different header.
    assert(
      withRequests(Seq.empty).map(_.requestsHash) == Right(Some(ExecutionRequests.hashOf(Vector.empty))) &&
        PayloadTranslation.headerOf(requestWithBeaconRoot(cancunish)).map(_.requestsHash) == Right(None),
      "an empty list commits, and an absent one does not"
    )

  it should "refuse an element carrying a type byte and no data" in
    // The refusal that has to happen BEFORE the hash. go-ethereum's commitment
    // skips such an element and the specification's hashes it, so a build that
    // hashed first would answer one of two values and report a block-hash
    // mismatch rather than invalid parameters.
    assert(
      withRequests(Seq(Bytes.fromIArray(IArray(0x01.toByte)))) ==
        Left(TranslationRefusal.RequestWithoutData(0)),
      "an element of one byte or shorter is refused, and the position is named"
    )

  it should "refuse an empty element" in
    assert(
      withRequests(Seq(PosFixtures.requestBytes, Bytes.Empty)) ==
        Left(TranslationRefusal.RequestWithoutData(1)),
      "nothing at all is shorter than one byte, and is the same refusal"
    )

  it should "refuse a list whose type bytes descend" in
    assert(
      withRequests(
        Seq(Bytes.fromIArray(IArray[Byte](0x02, 0x2a)), Bytes.fromIArray(IArray[Byte](0x01, 0x2a)))
      ) == Left(TranslationRefusal.RequestsOutOfOrder(1)),
      "the list is ordered by type ascending, so the commitment is over a sequence and not a set"
    )

  it should "refuse a list repeating a type byte" in
    assert(
      withRequests(Seq(PosFixtures.requestBytes, PosFixtures.requestBytes)) ==
        Left(TranslationRefusal.DuplicateRequestType(0x01)),
      "each type appears at most once, and the repeated one is named rather than its position"
    )

  it should "accept a list whose type bytes ascend" in
    // The control for the three refusals above: the same shape, ordered, with
    // data, is accepted -- so those are refusing the defect rather than refusing
    // every list.
    assert(
      withRequests(
        Seq(
          Bytes.fromIArray(IArray[Byte](0x00, 0x2a)),
          Bytes.fromIArray(IArray[Byte](0x01, 0x2a)),
          Bytes.fromIArray(IArray[Byte](0x02, 0x2a))
        )
      ).isRight,
      "a well-formed list of the three types Prague defines is not refused"
    )

  it should "refuse an empty transaction entry" in
    assert(
      PayloadTranslation.headerOf(NewPayloadRequest(paris.copy(transactions = Seq(Bytes.Empty)))) ==
        Left(TranslationRefusal.EmptyTransaction(0)),
      "the specification makes this check unconditional, and it is the one that needs no decoder"
    )

  it should "name the position of the empty entry" in
    assert(
      PayloadTranslation.headerOf(
        NewPayloadRequest(paris.copy(transactions = paris.transactions :+ Bytes.Empty))
      ) == Left(TranslationRefusal.EmptyTransaction(1)),
      "a payload carrying many transactions is undiagnosable without it"
    )

  "PayloadTranslation.checkedHeaderOf" should "report both hashes when they disagree" in
    assert(
      PayloadTranslation.checkedHeaderOf(NewPayloadRequest(paris.copy(blockHash = PosFixtures.hash(0x00)))) match
        case Left(TranslationRefusal.BlockHashMismatch(stated, derived)) =>
          stated == PosFixtures.hash(0x00) && derived == paris.blockHash
        case _ => false,
      "knowing only that they differ says nothing about which field was wrong"
    )

  it should "answer the derived header where they agree" in
    assert(
      PayloadTranslation.checkedHeaderOf(NewPayloadRequest(paris)).map(_.hash) == Right(paris.blockHash),
      "the stated hash stops being a claim here, and the header is what the caller goes on to execute"
    )

  it should "refuse a payload whose transactions were replaced by a different list" in
    assert(
      PayloadTranslation
        .checkedHeaderOf(NewPayloadRequest(paris.copy(transactions = shanghai.transactions)))
        .isLeft,
      "the transactions root is derived from the bytes the payload carries, so swapping them moves the hash"
    )

  "PayloadTranslation.EmptyOmmersHash" should "be the hash of an encoded empty header list" in
    assert(
      PayloadTranslation.EmptyOmmersHash ==
        org.fukuii.crypto.Keccak256.hash(org.fukuii.rlp.RlpCodec.encodeTo(Seq.empty[org.fukuii.types.BlockHeader])),
      "it is derived rather than written down, so nothing here carries thirty-two bytes with no derivation attached"
    )

  /** The comparison the specification makes a MUST at this boundary.
    *
    * ==Each of these is a separate way for the check to be absent==
    *
    * A build that never compared would pass the agreeing case and the
    * no-blob-transaction case on its own, so neither is evidence by itself.
    * What separates a check from no check is the disagreeing pair, the
    * non-empty argument against a payload carrying no blob transaction at all,
    * and the order — and the last of those is the one a comparison written over
    * sets rather than sequences still gets wrong.
    */
  "PayloadTranslation.headerOf" should "derive a header where the payload's blob hashes are the ones the call states" in
    assert(
      PayloadTranslation.headerOf(requestCarrying(Seq(blobTransaction(Seq(firstHash))), Seq(firstHash))).isRight,
      "a payload agreeing with its own third argument is the case the check must leave alone"
    )

  it should "refuse a payload whose blob hashes are not the ones the call states" in
    assert(
      PayloadTranslation.headerOf(requestCarrying(Seq(blobTransaction(Seq(firstHash))), Seq(secondHash))) ==
        Left(TranslationRefusal.BlobVersionedHashesMismatch(Seq(secondHash), Seq(firstHash))),
      "the refusal carries both arrays, because which entry differed is not recoverable from the fact that they did"
    )

  it should "concatenate several blob transactions in the order the payload includes them" in
    assert(
      PayloadTranslation.headerOf(
        requestCarrying(
          Seq(blobTransaction(Seq(firstHash)), blobTransaction(Seq(secondHash))),
          Seq(secondHash, firstHash)
        )
      ) == Left(TranslationRefusal.BlobVersionedHashesMismatch(Seq(secondHash, firstHash), Seq(firstHash, secondHash))),
      "the specification requires the actual array respect the order of inclusion, so a reversed argument is a mismatch"
    )

  it should "refuse a non-empty expected array against a payload carrying no blob transaction" in
    assert(
      PayloadTranslation.headerOf(requestCarrying(Seq.empty, Seq(firstHash))) ==
        Left(TranslationRefusal.BlobVersionedHashesMismatch(Seq(firstHash), Seq.empty)),
      "the specification requires the expected array be [] where the payload has no blob transaction"
    )

  it should "accept an empty expected array against a payload carrying no blob transaction" in
    assert(
      PayloadTranslation.headerOf(requestCarrying(Seq.empty, Seq.empty)).isRight,
      "an empty argument against no blob transactions is what every payload below this fork looks like"
    )

  it should "refuse a transaction entry it cannot decode, rather than counting it as carrying no blob hashes" in
    assert(
      PayloadTranslation.headerOf(
        requestCarrying(Seq(Bytes.fromIArray(IArray(0x03.toByte, 0xc0.toByte))), Seq.empty)
      ) match
        case Left(TranslationRefusal.UndecodableTransaction(0, _)) => true
        case _                                                     => false,
      "an entry whose blob hashes cannot be read is not an entry carrying none, and answering as though it were " +
        "would pass a payload by failing to read it"
    )

  it should "leave a payload alone where the call supplies no expected array at all" in
    assert(
      PayloadTranslation
        .headerOf(NewPayloadRequest(cancunish.copy(transactions = Seq(Bytes.fromIArray(IArray(0x03.toByte))))))
        .isRight,
      "the argument arrives with the third version of the method, so a request without one is asking nothing of " +
        "this check and its transactions are never decoded"
    )

  "PayloadTranslation.headerOf" should "read a header field count that matches the payload's own version" in
    assert(
      PayloadTranslation.headerOf(NewPayloadRequest(paris)).map(_.fieldCount) == Right(16) &&
        PayloadTranslation.headerOf(NewPayloadRequest(shanghai)).map(_.fieldCount) == Right(17),
      "the header's tail is built to the depth the payload's own appended chain reaches"
    )

  it should "build a deeper tail for a payload carrying blob-gas fields" in
    assert(
      PayloadTranslation.headerOf(NewPayloadRequest(cancunish)).map(_.fieldCount) == Right(19),
      "the blob-gas pair contributes two elements, which is the one step in the chain that is not one"
    )

  it should "reach the beacon-root link only behind the blob-gas pair" in
    assert(
      PayloadTranslation.headerOf(requestWithBeaconRoot(cancunish)).map(_.fieldCount) == Right(20),
      "the tail is positional, so the depth is what says which fields a reader will find"
    )

  "PayloadTranslation.contextOf" should "be total over a payload of any version" in
    assert(
      Seq(paris, shanghai, cancunish).map(p => PayloadTranslation.contextOf(p).prevRandao.isDefined) ==
        Seq(true, true, true),
      "every field it reads is mandatory on every version of the payload, so there is nothing to refuse"
    )

  it should "read the timestamp as an unsigned quantity" in
    assert(
      PayloadTranslation.contextOf(paris.copy(timestamp = UInt64.MaxValue)).timestamp == UInt64.MaxValue.toBigInt,
      "a timestamp past the signed range must widen to the unsigned value rather than to a negative one"
    )
