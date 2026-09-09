package org.fukuii.consensus.pos

import org.fukuii.bytes.{BytesError, FixedWidth, Hex}

/** The identifier of a payload build process.
  *
  * ==Eight bytes, and opaque to everyone but the execution layer==
  *
  * The specification types it `DATA`, 8 Bytes, and says nothing whatever about
  * its content: it is minted by `forkchoiceUpdated` when a build begins and
  * handed back to `getPayload` to name that build. The only requirement stated
  * over it is uniqueness — `ethereum/execution-apis` @ `6570b55`
  * `src/engine/paris.md:142`, *"Every new build process MUST be uniquely
  * identified by the returned `payloadId` value."*
  *
  * ==The two clients read for it derive it differently, which is why nothing
  * here interprets it==
  *
  * `ethereum/go-ethereum` @ `02872e9ef` `beacon/engine/types.go:201` declares
  * `type PayloadID [8]byte` and then reads its FIRST BYTE as a private version
  * tag (`:204-206`, `func (b PayloadID) Version()`).
  * `besu-eth/besu` @ `b330564a94`
  * `consensus/merge/.../PayloadIdentifier.java:28` holds a `UInt64` instead,
  * derived at `:56-93` by folding the build parameters together with shifts and
  * exclusive-ors.
  *
  * Neither derivation is specified, and adopting either would be adopting one
  * client's private encoding as though it were the protocol. **So this carries
  * the eight bytes and offers no reading of them**, which is the only shape
  * both clients' identifiers fit into.
  *
  * ==Fixed-width, for the reason [[org.fukuii.types.BlockNonce]] is==
  *
  * `DATA` is a byte string whose leading zeros are part of it, where `QUANTITY`
  * drops them. An identifier held as a number and rendered as a quantity would
  * shorten whenever its first byte happened to be zero, and the consensus layer
  * would hand back a string the execution layer never issued.
  */
final class PayloadId private (private val raw: IArray[Byte]):

  def toBytes: IArray[Byte] = raw

  def toHex: String = Hex.encode(raw)

  override def equals(that: Any): Boolean = that match
    case other: PayloadId => FixedWidth.sameBytes(raw, other.raw)
    case _                => false

  override def hashCode(): Int = FixedWidth.hash(raw)

  override def toString: String = "0x" + Hex.encode(raw)

object PayloadId:

  val Width: Int = 8

  /** Requires exactly [[Width]] bytes. */
  def fromBytes(bytes: IArray[Byte]): Either[BytesError, PayloadId] =
    if bytes.length == Width then Right(new PayloadId(FixedWidth.align(bytes, Width)))
    else Left(BytesError.BadWidth(Width, bytes.length))

  def fromHex(s: String): Either[BytesError, PayloadId] =
    Hex.decode(s).left.map(BytesError.BadHex.apply).flatMap(fromBytes)
