package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Bytes, UInt64}
import org.fukuii.types.Seal

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
  private val beaconRoot = PosFixtures.hash(0xbe)

  private def requestWithBeaconRoot(payload: ExecutionPayload): NewPayloadRequest =
    NewPayloadRequest(payload, Some(BlobAndBeaconArguments(Seq.empty, beaconRoot)))

  private def sealOf(payload: ExecutionPayload): Option[Seal] =
    PayloadTranslation.headerOf(NewPayloadRequest(payload)).toOption.map(_.seal)

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

  it should "refuse a request carrying execution requests" in
    assert(
      PayloadTranslation.headerOf(
        NewPayloadRequest(
          cancunish,
          Some(
            BlobAndBeaconArguments(
              Seq.empty,
              beaconRoot,
              Some(ExecutionRequestsArgument(Seq(PosFixtures.requestBytes)))
            )
          )
        )
      ) == Left(TranslationRefusal.ExecutionRequestsNotDerived),
      "deriving nothing would surface as a block-hash mismatch, which names the wrong defect"
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
