package org.fukuii.consensus.pos

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.types.{Bloom, Withdrawal}

/** What more than one spec in this module needs and none of them should build
  * twice.
  *
  * Every field a test does not read is left at its zero rather than invented: a
  * plausible value in a field nothing reads suggests something stated it, and
  * nothing here did.
  */
object PosFixtures:

  def hash(byte: Int): Hash = Hash.fromBytesTruncating(IArray.fill(Hash.Width)(byte.toByte))

  def address(byte: Int): Address = Address.fromBytesTruncating(IArray.fill(Address.Width)(byte.toByte))

  val withdrawal: Withdrawal = Withdrawal(UInt64.fromBits(1L), UInt64.fromBits(2L), address(0x03), UInt64.fromBits(4L))

  /** The fourteen fields every version of the structure carries.
    *
    * Named for what it is rather than for the fork it belongs to: it is the
    * whole of an `ExecutionPayloadV1`, and every later version is this plus
    * appended links.
    */
  val bareCore: ExecutionPayload = ExecutionPayload(
    parentHash = hash(0x11),
    feeRecipient = address(0x22),
    stateRoot = hash(0x33),
    receiptsRoot = hash(0x44),
    logsBloom = Bloom.Empty,
    prevRandao = hash(0x55),
    blockNumber = UInt64.fromBits(7L),
    gasLimit = UInt64.fromBits(30000000L),
    gasUsed = UInt64.fromBits(21000L),
    timestamp = UInt64.fromBits(1710338135L),
    extraData = Bytes.Empty,
    baseFeePerGas = UInt256.Zero,
    blockHash = hash(0x66),
    transactions = Seq.empty
  )

  val withWithdrawals: ExecutionPayload =
    bareCore.copy(appended = Some(PayloadWithdrawals(Seq(withdrawal))))

  val withBlobGas: ExecutionPayload =
    bareCore.copy(
      appended = Some(
        PayloadWithdrawals(
          Seq(withdrawal),
          Some(PayloadBlobGas(UInt64.fromBits(131072L), UInt64.fromBits(262144L)))
        )
      )
    )

  val bareAttributes: PayloadAttributes = PayloadAttributes(
    timestamp = UInt64.fromBits(1710338135L),
    prevRandao = hash(0x77),
    suggestedFeeRecipient = address(0x88)
  )

  val attributesWithWithdrawals: PayloadAttributes =
    bareAttributes.copy(appended = Some(AttributesWithdrawals(Seq(withdrawal))))

  val attributesWithBeaconRoot: PayloadAttributes =
    bareAttributes.copy(
      appended = Some(AttributesWithdrawals(Seq(withdrawal), Some(AttributesBeaconRoot(hash(0x99)))))
    )

  /** A stand-in for what a network family would append.
    *
    * It carries a field so that the test reads a value back rather than merely
    * observing that a slot accepted something — an empty case would pass the
    * same assertion whether or not the value survived the round trip.
    */
  final case class SampleFamilyPayloadFields(marker: Int) extends ExecutionPayload.FamilyFields

  final case class SampleFamilyAttributeFields(marker: Int) extends PayloadAttributes.FamilyFields
