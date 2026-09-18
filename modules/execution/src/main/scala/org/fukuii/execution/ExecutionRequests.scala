package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.crypto.{Keccak256, Sha256}
import org.fukuii.types.Log

/** What the execution layer asks of the consensus layer, and the commitment a
  * header states over it.
  *
  * ==Three sources, and only two of them are system calls==
  *
  * A block's request list is assembled from deposits parsed out of its own
  * receipts, then the return data of two checked system calls, in that order.
  * **The first is not a call at all**, which is why a rule expressed as "check
  * every new system call" cannot see it: nothing is invoked, the records are
  * read out of logs the transactions already emitted.
  *
  * `ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/fork.py:783-810`.
  *
  * ==Order is normative, so the list is not a set==
  *
  * *"Requests are to be in ascending order of request type"* (`fork.py:783`).
  * A correct list in the wrong order states a different commitment, so the
  * assembly order is the rule rather than an implementation detail.
  *
  * ==A source that produced nothing contributes NO RECORD==
  *
  * Each of the three appends only where it produced bytes. An empty record is
  * not the same as an absent one: the commitment hashes each record it holds,
  * so an empty entry would change the header's value. This is the distinction
  * `.claude/protocols/consensus-change.md` records for the seam generally,
  * arriving here as the thing that decides a header field.
  *
  * ==The commitment is SHA2-256, NOT keccak==
  *
  * `compute_requests_hash` (`requests.py:289-308`) hashes each request with
  * SHA2-256 and then hashes the concatenation of those digests with SHA2-256
  * again. **Every other commitment a header in this build states is keccak**, so
  * this is the one place reaching for the usual digest produces a plausible
  * 32-byte value that is wrong, with nothing about the shape to signal it.
  */
object ExecutionRequests:

  /** The first topic a deposit event carries.
    *
    * **Derived rather than transcribed.** It is the keccak of the contract's
    * Solidity event signature, so it is computed here from that signature and
    * `ExecutionRequestsSpec` checks the result against the value two independent
    * sources state -- `ethereum/execution-specs` @ `0cc100eb1`
    * `forks/prague/requests.py:56-58` and `besu-eth/besu` @ `b330564a94`
    * `DepositRequestProcessor.java:34-37`. A thirty-two byte literal copied by
    * hand has nothing about its shape to signal a slip, and this section has
    * already corrupted one published record that way.
    *
    * **A constant rather than a parameter, unlike the contract's address.** No
    * network varies it: it follows from the event's own ABI, and besu -- which
    * does make the address configurable -- still holds this one `static final`.
    * A caller that could supply it could supply a wrong one, and every deposit
    * in the block would then be silently ignored rather than refused.
    */
  val DepositEventSignature: Hash =
    Keccak256.hash(IArray.from("DepositEvent(bytes,bytes,bytes,bytes,bytes)".getBytes("US-ASCII")))

  /** The type byte a deposit record carries. */
  val DepositType: Byte = 0x00

  /** The type byte an execution-triggered withdrawal record carries. */
  val WithdrawalType: Byte = 0x01

  /** The type byte a consolidation record carries. */
  val ConsolidationType: Byte = 0x02

  /** The commitment over an ordered list of type-prefixed records.
    *
    * **A block producing no records still states a commitment** -- the hash of
    * an empty concatenation -- which is a different value from stating none at
    * all. A fork below the container states none; a fork with it states this.
    */
  def hashOf(requests: Vector[Bytes]): Hash =
    val inner = requests.map(request => Sha256.hash(request.toIArray).toBytes)
    Hash.fromBytesTruncating(Sha256.hash(inner.foldLeft(IArray.empty[Byte])(_ ++ _)).toBytes)

  /** Every deposit the block's own receipts record, concatenated.
    *
    * ==A layout deviation refuses the BLOCK==
    *
    * The deposit contract emits a fixed-shape event, so every well-formed one
    * has an identical byte layout. The specification refuses the block on any
    * deviation rather than skipping the record, on the reasoning that a
    * deviation means the contract is misbehaving or compromised -- so a lenient
    * parser here would accept a block the network rejects, and would do it
    * precisely when something is wrong.
    *
    * `requests.py:150-252` states each check; there are ten, five offsets and
    * five sizes, and each refuses.
    */
  def depositsIn(
      receiptLogs: Vector[Log],
      depositContract: Address,
      eventSignature: Hash
  ): Either[RequestFault, Bytes] =
    val emitted =
      receiptLogs.filter(log => log.address == depositContract && log.topics.headOption.contains(eventSignature))
    emitted.foldLeft[Either[RequestFault, Bytes]](Right(Bytes.Empty)): (carried, log) =>
      for
        sofar <- carried
        fields <- fieldsOf(log.data)
      yield Bytes.fromIArray(sofar.toIArray ++ fields.toIArray)

  /** The five fields of one deposit event, in the order the consensus layer
    * consumes them, with the encoder's framing stripped.
    */
  private def fieldsOf(data: Bytes): Either[RequestFault, Bytes] =
    val raw = data.toIArray
    if raw.length != EventLength then Left(RequestFault.DepositLayout)
    else
      val offsets = Offsets.zipWithIndex.forall((expected, slot) => wordAt(raw, slot * 32).contains(expected))
      if !offsets then Left(RequestFault.DepositLayout)
      else
        val sized = Offsets.zip(Sizes).forall((offset, size) => wordAt(raw, offset).contains(size))
        if !sized then Left(RequestFault.DepositLayout)
        else
          val fields =
            Offsets
              .zip(Sizes)
              .foldLeft(IArray.empty[Byte]): (carried, pair) =>
                val (offset, size) = pair
                carried ++ raw.slice(offset + 32, offset + 32 + size)
          Right(Bytes.fromIArray(fields))

  /** The total width of a well-formed event payload. */
  private val EventLength: Int = 576

  /** Where each field's length prefix sits, in the encoder's own order. */
  private val Offsets: Vector[Int] = Vector(160, 256, 320, 384, 512)

  /** How wide each field is: key, credentials, amount, signature, index. */
  private val Sizes: Vector[Int] = Vector(48, 32, 8, 96, 8)

  /** The word at `at`, or nothing where it does not fit a machine integer.
    *
    * **The width matters and folding it into an `Int` silently would not be
    * safe.** These words are encoder offsets and field lengths, all small; a
    * malformed event can state any 32-byte value, and accumulating one into an
    * `Int` wraps after four bytes -- so a stated offset of `2^32 + 160` would
    * read as `160` and pass a check it should fail. The high bytes are required
    * to be zero instead, which refuses such a value rather than truncating it.
    */
  private def wordAt(raw: IArray[Byte], at: Int): Option[Int] =
    var high = 0
    var index = at
    while index < at + 28 do
      high = high | (raw(index) & 0xff)
      index += 1
    if high != 0 then None
    else
      var value = 0L
      var low = at + 28
      while low < at + 32 do
        value = (value << 8) | (raw(low) & 0xffL)
        low += 1
      if value > Int.MaxValue then None else Some(value.toInt)

/** Why a block's request list could not be assembled. */
enum RequestFault:

  /** A deposit event whose byte layout is not the one the contract emits.
    *
    * **The block is refused rather than the record skipped.** A deviation means
    * the contract is misbehaving, and a parser that skipped it would accept a
    * block every conforming client rejects.
    */
  case DepositLayout
