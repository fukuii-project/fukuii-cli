package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.Log
import org.scalatest.flatspec.AnyFlatSpec

/** The commitment a header states over a block's requests, and the parse that
  * feeds it.
  *
  * ==The commitment is checked against published headers, not against itself==
  *
  * Each vector below is a request list taken from a published engine payload,
  * paired with the `requestsHash` the SAME case's block-tier header states. So
  * the construction is pinned by two independent tiers of the corpus agreeing,
  * which a hash this build computed and then re-computed could not do.
  *
  * **That matters more here than for most commitments**, because this one is
  * SHA2-256 where every other header commitment in this build is keccak.
  * Reaching for the usual digest produces a plausible thirty-two bytes with
  * nothing about its shape to signal the mistake.
  */
class ExecutionRequestsSpec extends AnyFlatSpec:

  private def bytesOf(hex: String): Bytes =
    Bytes.fromArray(hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray)

  private def hashOf(hex: String): Hash =
    Hash.fromBytesTruncating(bytesOf(hex).toIArray)

  /** A published withdrawal-request record, type `0x01`. */
  private val withdrawalRecord: Bytes =
    bytesOf(
      "013ce21c3c5b42c2c89cb5db657dbbe9545b916a54000000000000000000000000000000000000000000000000000000" +
        "00000000000000000000000000000000000000004e00000000000000003ce21c3c5b42c2c89cb5db657dbbe9545b916a" +
        "540000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "4f00000000000000003ce21c3c5b42c2c89cb5db657dbbe9545b916a5400000000000000000000000000000000000000" +
        "00000000000000000000000000000000000000000000000000000000500000000000000000"
    )

  private val withdrawalCommitment: Hash =
    hashOf("4ecfe8899e3730f37fd1de92eeae72b2b3dd1d12f32931b14eb9fa3e4b6a0711")

  /** A published consolidation-request record, type `0x02`. */
  private val consolidationRecord: Bytes =
    bytesOf(
      "028d358b49a46940b0b72aaf71d17d208e6d7e2bde000000000000000000000000000000000000000000000000000000" +
        "00000000000000000000000000000000000000003c000000000000000000000000000000000000000000000000000000" +
        "00000000000000000000000000000000000000003d8d358b49a46940b0b72aaf71d17d208e6d7e2bde00000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000003e00000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000003f"
    )

  private val consolidationCommitment: Hash =
    hashOf("197c7d9aa3fc68f54339cc9269485d61d3c2a2373183d414ad313ab0e0da6200")

  private val depositContract: Address = EvmFixtures.address(0x42)

  private val eventSignature: Hash = ExecutionRequests.DepositEventSignature

  /** What two independent sources state the topic is, which the derivation above
    * has to reproduce.
    */
  private val publishedEventSignature: Hash =
    hashOf("649bbc62d0e31342afea4e5cd82d4049e7e1ee912fc0889aa790803be39038c5")

  "the deposit event's topic" should "be the keccak of the event's own signature" in
    // Derived from the ABI signature rather than copied in as thirty-two bytes,
    // and checked here against what `ethereum/execution-specs` @ `0cc100eb1`
    // (`forks/prague/requests.py:56-58`) and `besu-eth/besu` @ `b330564a94`
    // (`DepositRequestProcessor.java:34-37`) each state. A literal that had been
    // mistyped would look exactly like one that had not.
    assert(
      ExecutionRequests.DepositEventSignature == publishedEventSignature,
      "the derivation agrees with the value two sources that do not share a lineage publish"
    )

  "the requests commitment" should "reproduce a published withdrawal block's header value" in
    assert(
      ExecutionRequests.hashOf(Vector(withdrawalRecord)) == withdrawalCommitment,
      "the record came from an engine payload and the hash from the same case's block header"
    )

  it should "reproduce a published consolidation block's header value" in
    // A second record type, so one agreement is not a coincidence of the first
    // record's shape.
    assert(
      ExecutionRequests.hashOf(Vector(consolidationRecord)) == consolidationCommitment,
      "a different record type, independently pinned"
    )

  it should "give the two different values" in
    // The control. A construction ignoring its input would satisfy each case
    // above only if both expected the same hash, and they do not.
    assert(
      ExecutionRequests.hashOf(Vector(withdrawalRecord)) != ExecutionRequests.hashOf(Vector(consolidationRecord)),
      "two records, two commitments"
    )

  it should "state a value for a block that produced no records" in
    // Not the same as stating no commitment. A fork below the container states
    // none; a fork with it and an empty list states this, and the two are
    // different header values.
    assert(
      ExecutionRequests.hashOf(Vector.empty).toBytes.length == 32,
      "an empty list still commits, to the hash of nothing"
    )

  it should "depend on the order records are listed in" in
    // Order is normative -- the specification requires ascending type order --
    // so a correct list assembled in the wrong order states a different
    // commitment. A build treating the list as a set would pass every case
    // above and fail here.
    assert(
      ExecutionRequests.hashOf(Vector(withdrawalRecord, consolidationRecord)) !=
        ExecutionRequests.hashOf(Vector(consolidationRecord, withdrawalRecord)),
      "the list is a sequence, and reordering it changes what the header states"
    )

  // ── The deposit parse, whose failures refuse the block ────────────────────

  /** A well-formed event payload: five offsets, then each field's length
    * followed by its bytes, padded to the encoder's fixed width.
    */
  private def wellFormedEvent: Bytes =
    val raw = new Array[Byte](576)
    def putWord(at: Int, value: Int): Unit =
      var v = value
      var i = at + 31
      while i >= at + 28 do
        raw(i) = (v & 0xff).toByte
        v = v >>> 8
        i -= 1
    Seq(160, 256, 320, 384, 512).zipWithIndex.foreach((offset, slot) => putWord(slot * 32, offset))
    Seq((160, 48), (256, 32), (320, 8), (384, 96), (512, 8)).foreach((offset, size) => putWord(offset, size))
    Bytes.fromArray(raw)

  private def loggedBy(address: Address, topic: Hash, data: Bytes): Log =
    Log(address, Vector(topic), data)

  "a well-formed deposit event" should "yield the five fields concatenated" in
    assert(
      ExecutionRequests
        .depositsIn(Vector(loggedBy(depositContract, eventSignature, wellFormedEvent)), depositContract, eventSignature)
        .map(_.length) == Right(48 + 32 + 8 + 96 + 8),
      "the framing is stripped and the fields are left in the order the consensus layer consumes them"
    )

  "a log from another account" should "contribute nothing" in
    assert(
      ExecutionRequests
        .depositsIn(
          Vector(loggedBy(EvmFixtures.address(0x99), eventSignature, wellFormedEvent)),
          depositContract,
          eventSignature
        ) == Right(Bytes.Empty),
      "only the deposit contract's own events are deposits"
    )

  "a log under another topic" should "contribute nothing" in
    assert(
      ExecutionRequests
        .depositsIn(
          Vector(loggedBy(depositContract, hashOf("00" * 32), wellFormedEvent)),
          depositContract,
          eventSignature
        ) == Right(Bytes.Empty),
      "the contract emits more than one event, and only one of them is a deposit"
    )

  "an event of the wrong width" should "refuse the block" in
    assert(
      ExecutionRequests
        .depositsIn(
          Vector(loggedBy(depositContract, eventSignature, Bytes.fromArray(new Array[Byte](575)))),
          depositContract,
          eventSignature
        ) == Left(RequestFault.DepositLayout),
      "a deviation means the contract is misbehaving, so the block is refused rather than the record skipped"
    )

  "an event whose offset is wrong" should "refuse the block" in {
    val raw = IArray.genericWrapArray(wellFormedEvent.toIArray).toArray
    raw(31) = (raw(31) + 1).toByte
    assert(
      ExecutionRequests
        .depositsIn(
          Vector(loggedBy(depositContract, eventSignature, Bytes.fromArray(raw))),
          depositContract,
          eventSignature
        )
        == Left(RequestFault.DepositLayout),
      "each of the five offsets is checked, not just the payload's width"
    )
  }

  "an offset stated above a machine integer" should "refuse the block" in {
    // The overflow this parse would otherwise have: accumulating a 32-byte word
    // into an Int wraps after four bytes, so a stated offset of 2^32 + 160
    // would read as 160 and pass a check it must fail.
    val raw = IArray.genericWrapArray(wellFormedEvent.toIArray).toArray
    raw(27) = 0x01.toByte
    assert(
      ExecutionRequests
        .depositsIn(
          Vector(loggedBy(depositContract, eventSignature, Bytes.fromArray(raw))),
          depositContract,
          eventSignature
        )
        == Left(RequestFault.DepositLayout),
      "a word wider than a machine integer is refused rather than truncated into a valid one"
    )
  }

  // ── An authored supplement: offsets that are plausible and misplaced ──────

  "an event whose offsets are swapped" should "refuse the block" in {
    // AUTHORED HERE, and the reason is what the published set does not cover.
    // `tests-v20.0.1` states 22 layout cases under `for_prague`, each setting
    // one of the ten words to zero or to the maximum a word can hold. Both are
    // values no encoder produces, so a parse testing only whether an offset
    // looks plausible passes every one of them. **Swapping two offsets is the
    // case that separates plausible from correct**: each value is one the
    // encoder really does emit, and only comparing each slot against the offset
    // that belongs in it refuses the pair.
    //
    // ==The field splits on whether this is checked at all==
    //
    // `ethereum/execution-specs` @ `0cc100eb1` `forks/prague/requests.py` checks
    // all ten words and refuses, and `NethermindEth/nethermind` @ `3a98e0818`
    // does the same in `ExecutionRequestsProcessor.cs:197-213`, throwing
    // `InvalidBlockException`. `ethereum/go-ethereum` @ `02872e9ef` checks the
    // total length alone and then reads at fixed offsets
    // (`core/types/deposit.go:29-32`), and `besu-eth/besu` @ `b330564a9` does
    // the same and says so -- *"we are only interested in the data fields and
    // will skip over the position and length fields"*
    // (`DepositLogDecoder.java`). **This build follows the specification and
    // the client that agrees with it**, which is the rule
    // `.claude/protocols/consensus-change.md` states for an observable split
    // between production clients: implement it, and record who does not.
    //
    // **Reachable only through an authored genesis**, because a conforming
    // deposit contract cannot emit one -- which is why no live network can tell
    // the two readings apart and why the case has to be authored to exist.
    val raw = IArray.genericWrapArray(wellFormedEvent.toIArray).toArray
    def putWord(at: Int, value: Int): Unit =
      var v = value
      var i = at + 31
      while i >= at + 28 do
        raw(i) = (v & 0xff).toByte
        v = v >>> 8
        i -= 1
    // The first two offsets exchanged: 256 where 160 belongs and 160 where 256
    // does. Both are offsets this encoder emits, in the wrong slots.
    putWord(0, 256)
    putWord(32, 160)
    assert(
      ExecutionRequests
        .depositsIn(
          Vector(loggedBy(depositContract, eventSignature, Bytes.fromArray(raw))),
          depositContract,
          eventSignature
        ) == Left(RequestFault.DepositLayout),
      "each slot is held to the offset that belongs in it, not merely to being an offset"
    )
  }

  it should "still accept the same event with the offsets in their own slots" in
    // The control for the case above. Same widths, same payload, same parse --
    // only the two offsets restored -- so the refusal is of the swap rather than
    // of anything else the authored event does.
    assert(
      ExecutionRequests
        .depositsIn(Vector(loggedBy(depositContract, eventSignature, wellFormedEvent)), depositContract, eventSignature)
        .isRight,
      "the swap is what is refused, and nothing about the authored event otherwise is"
    )
