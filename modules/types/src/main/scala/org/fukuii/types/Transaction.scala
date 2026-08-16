package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, UInt256, UInt64}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.{Rlp, RlpCodec, RlpError, RlpItem}

/** A transaction, in the typed envelope EIP-2718 defines.
  *
  * ==The envelope, and why it is a sum rather than a tag plus a payload==
  *
  * EIP-2718 says a transaction is either `TransactionType || TransactionPayload`
  * or a `LegacyTransaction`, where the type is "a positive unsigned 8-bit
  * number between 0 and 0x7f". Each payload has its own field list, so the tag
  * and the fields are not independent — a tag with the wrong field list is not
  * a transaction. Modelling them as one sum makes that unrepresentable, and
  * makes a match over the set exhaustive.
  *
  * ==Which types a NETWORK admits is not decided here==
  *
  * This models the five payload types whose proposals are `Final`, and decodes
  * every one of them on every network. **That is deliberate and it is not the
  * same question as which types a network accepts.** A network that does not
  * admit some type still has to decode one to reject it — otherwise it cannot
  * tell a malformed transaction from a well-formed one that is not admitted
  * here, and those call for different responses to the peer that sent it.
  *
  * So admission is a predicate above this layer, over [[typeNumber]], owned by
  * whichever layer holds the network's fork rules. Nothing here encodes a
  * network's answer, and a reader wanting one reads that network's own
  * specification rather than this file.
  *
  * ==The set is closed==
  *
  * A new payload type arrives by adding a case here, not by registering one at
  * run time. One reference client does offer a registry; the cost is that the
  * set is no longer known at compile time, so a match over it stops being
  * exhaustive and a missing instance stops being a compile error. That trade
  * is the one this project's codec layer exists to refuse.
  *
  * @see
  *   [[Transaction.canonicalBytes]] for the form a hash is taken over, which is
  *   NOT what the codec produces for a typed transaction. The difference is
  *   the subtlest thing in this file and is documented there.
  */
sealed trait Transaction:

  /** The EIP-2718 type number: 0 for the legacy shape, and the value the
    * payload's own proposal states otherwise.
    *
    * First-class rather than recovered by matching, because the layer that
    * decides whether this network admits this type needs exactly this and
    * nothing else about the transaction.
    */
  def typeNumber: Int

  def nonce: UInt64
  def gasLimit: UInt64
  def value: UInt256
  def data: Bytes

  /** The recipient, absent when the transaction creates a contract.
    *
    * Absent is unrepresentable for two of the five payloads, whose own
    * proposals require a recipient — so this is an `Option` here and a plain
    * address on those cases.
    */
  def to: Option[Address]

  /** `keccak(canonicalBytes)` — the identifier a receipt, a trie and a peer
    * all refer to this transaction by.
    *
    * A `lazy val` on each case rather than a method, for the reason
    * [[BlockHeader.hash]] states: it is asked for repeatedly, it is a digest,
    * and every field is immutable, so it cannot change.
    */
  def hash: Hash

object Transaction:

  /** The legacy shape, which carries no type byte at all.
    *
    * @param v
    *   carries the signature parity and, since EIP-155, the chain identifier
    *   folded into it. It is NOT the parity alone, which is why it is `v` here
    *   and `yParity` on every typed payload — reading one as the other is a
    *   wrong sender on every replay-protected transaction.
    */
  final case class Legacy(
      nonce: UInt64,
      gasPrice: UInt256,
      gasLimit: UInt64,
      to: Option[Address],
      value: UInt256,
      data: Bytes,
      v: UInt256,
      r: UInt256,
      s: UInt256
  ) extends Transaction:
    def typeNumber: Int  = Legacy.TypeNumber
    lazy val hash: Hash  = Keccak256.hash(canonicalBytes(this))

  /** EIP-2930's payload: the legacy fields, a chain identifier, and an access
    * list.
    */
  final case class AccessList(
      chainId: UInt64,
      nonce: UInt64,
      gasPrice: UInt256,
      gasLimit: UInt64,
      to: Option[Address],
      value: UInt256,
      data: Bytes,
      accessList: Seq[AccessTuple],
      yParity: UInt256,
      r: UInt256,
      s: UInt256
  ) extends Transaction:
    def typeNumber: Int = AccessList.TypeNumber
    lazy val hash: Hash = Keccak256.hash(canonicalBytes(this))

  /** EIP-1559's payload: one gas price becomes a cap and a tip. */
  final case class DynamicFee(
      chainId: UInt64,
      nonce: UInt64,
      maxPriorityFeePerGas: UInt256,
      maxFeePerGas: UInt256,
      gasLimit: UInt64,
      to: Option[Address],
      value: UInt256,
      data: Bytes,
      accessList: Seq[AccessTuple],
      yParity: UInt256,
      r: UInt256,
      s: UInt256
  ) extends Transaction:
    def typeNumber: Int = DynamicFee.TypeNumber
    lazy val hash: Hash = Keccak256.hash(canonicalBytes(this))

  /** EIP-4844's payload.
    *
    * @param to
    *   a plain address, not an option: this payload's own proposal types the
    *   field as an address rather than the address-or-empty every earlier
    *   payload carries, so a blob transaction cannot create a contract and the
    *   type says so.
    * @param blobVersionedHashes
    *   the commitments' versioned hashes. The blobs themselves travel beside
    *   the transaction on the network layer and are not part of it here.
    */
  final case class Blob(
      chainId: UInt64,
      nonce: UInt64,
      maxPriorityFeePerGas: UInt256,
      maxFeePerGas: UInt256,
      gasLimit: UInt64,
      recipient: Address,
      value: UInt256,
      data: Bytes,
      accessList: Seq[AccessTuple],
      maxFeePerBlobGas: UInt256,
      blobVersionedHashes: Seq[Hash],
      yParity: UInt256,
      r: UInt256,
      s: UInt256
  ) extends Transaction:
    def typeNumber: Int      = Blob.TypeNumber
    def to: Option[Address]  = Some(recipient)
    lazy val hash: Hash      = Keccak256.hash(canonicalBytes(this))

  /** EIP-7702's payload.
    *
    * @param recipient
    *   a plain address, for the same reason [[Blob]]'s is — the proposal notes
    *   in as many words that a null destination is not valid.
    * @param authorizationList
    *   the proposal calls a transaction with an empty list invalid. That is a
    *   validity rule rather than an encoding one — an empty list encodes and
    *   decodes unambiguously — so it is not refused here, and the corpus
    *   agrees by giving it its own expected exception separate from the
    *   malformed-tuple one.
    */
  final case class SetCode(
      chainId: UInt64,
      nonce: UInt64,
      maxPriorityFeePerGas: UInt256,
      maxFeePerGas: UInt256,
      gasLimit: UInt64,
      recipient: Address,
      value: UInt256,
      data: Bytes,
      accessList: Seq[AccessTuple],
      authorizationList: Seq[Authorization],
      yParity: UInt256,
      r: UInt256,
      s: UInt256
  ) extends Transaction:
    def typeNumber: Int     = SetCode.TypeNumber
    def to: Option[Address] = Some(recipient)
    lazy val hash: Hash     = Keccak256.hash(canonicalBytes(this))

  object Legacy:
    /** Not a number any proposal assigns — the legacy shape predates the
      * envelope and carries no type byte. Zero is the ecosystem's name for
      * "the untyped one" and is what the conformance fixtures report.
      */
    val TypeNumber: Int  = 0x00
    val FieldCount: Int  = 9

  object AccessList:
    val TypeNumber: Int = 0x01
    val FieldCount: Int = 11

  object DynamicFee:
    val TypeNumber: Int = 0x02
    val FieldCount: Int = 12

  object Blob:
    val TypeNumber: Int = 0x03
    val FieldCount: Int = 14

  object SetCode:
    val TypeNumber: Int = 0x04
    val FieldCount: Int = 13

  /** The largest value EIP-2718 admits as a type byte. Anything above it is
    * not an unknown type — it is not a type at all.
    */
  val MaxTypeNumber: Int = 0x7f

  /** Every payload ends with its three signature elements, and every one of
    * the five ends with exactly three.
    *
    * That is structural rather than a coincidence worth leaning on carefully:
    * the legacy shape ends `v, r, s` and each typed payload ends `yParity, r,
    * s`. It is what lets the signing projection be one rule instead of five,
    * and each resulting length was checked against its own proposal — 9 to 6,
    * 11 to 8, 12 to 9, 14 to 11, 13 to 10.
    */
  val SignatureFieldCount: Int = 3

  /** The payload's elements, signature included.
    *
    * Visible inside this package so the signing projection can drop the
    * signature from it, rather than restating five field lists that would then
    * have to be kept in step with the ones above.
    */
  private[types] def elementsOf(transaction: Transaction): Vector[RlpItem] =
    payloadFields(transaction)

  /** The form a transaction hash is taken over, and the form that travels as
    * a whole transaction: `type || rlp(payload)` for a typed transaction, and
    * plain `rlp([...])` for the legacy one.
    *
    * ==This is NOT what the codec encodes a typed transaction to==
    *
    * Inside a block body a transaction is an element of an RLP list, and these
    * bytes are not an RLP item — so a typed transaction appears there as an RLP
    * BYTE STRING wrapping them, which adds a length header. Hashing that
    * wrapped form, or putting these unwrapped bytes into a list, is a wrong
    * transaction hash and a wrong transactions root.
    *
    * Measured rather than reasoned: across 42,515 transactions of all five
    * types in blocks the conformance corpus publishes, every legacy
    * transaction is a list element and every typed one is a string element
    * whose first payload byte is its type number.
    */
  def canonicalBytes(transaction: Transaction): IArray[Byte] = transaction match
    case t: Legacy => Rlp.encode(RlpItem.Sequence(legacyFields(t)))
    case other =>
      val body = Rlp.encode(RlpItem.Sequence(payloadFields(other)))
      IArray(other.typeNumber.toByte) ++ body

  /** Reads the form [[canonicalBytes]] produces. */
  def fromCanonicalBytes(bytes: IArray[Byte]): Either[RlpError, Transaction] =
    if bytes.isEmpty then Left(RlpError.EmptyInput)
    else
      val head = bytes(0) & 0xff
      if head > MaxTypeNumber then Rlp.decode(bytes).flatMap(decodeLegacyItem)
      else Rlp.decode(bytes.drop(1)).flatMap(item => decodeTypedPayload(head, item))

  /** `[nonce, gasPrice, gasLimit, to, value, data, v, r, s]` — EIP-2718 states
    * the legacy shape in exactly these words.
    */
  private def legacyFields(t: Legacy): Vector[RlpItem] =
    Vector(
      RlpCodec[UInt64].encode(t.nonce),
      RlpCodec[UInt256].encode(t.gasPrice),
      RlpCodec[UInt64].encode(t.gasLimit),
      encodeRecipient(t.to),
      RlpCodec[UInt256].encode(t.value),
      RlpCodec[Bytes].encode(t.data),
      RlpCodec[UInt256].encode(t.v),
      RlpCodec[UInt256].encode(t.r),
      RlpCodec[UInt256].encode(t.s)
    )

  private def payloadFields(transaction: Transaction): Vector[RlpItem] = transaction match
    case t: Legacy => legacyFields(t)

    case t: AccessList =>
      Vector(
        RlpCodec[UInt64].encode(t.chainId),
        RlpCodec[UInt64].encode(t.nonce),
        RlpCodec[UInt256].encode(t.gasPrice),
        RlpCodec[UInt64].encode(t.gasLimit),
        encodeRecipient(t.to),
        RlpCodec[UInt256].encode(t.value),
        RlpCodec[Bytes].encode(t.data),
        RlpCodec[Seq[AccessTuple]].encode(t.accessList),
        RlpCodec[UInt256].encode(t.yParity),
        RlpCodec[UInt256].encode(t.r),
        RlpCodec[UInt256].encode(t.s)
      )

    case t: DynamicFee =>
      Vector(
        RlpCodec[UInt64].encode(t.chainId),
        RlpCodec[UInt64].encode(t.nonce),
        RlpCodec[UInt256].encode(t.maxPriorityFeePerGas),
        RlpCodec[UInt256].encode(t.maxFeePerGas),
        RlpCodec[UInt64].encode(t.gasLimit),
        encodeRecipient(t.to),
        RlpCodec[UInt256].encode(t.value),
        RlpCodec[Bytes].encode(t.data),
        RlpCodec[Seq[AccessTuple]].encode(t.accessList),
        RlpCodec[UInt256].encode(t.yParity),
        RlpCodec[UInt256].encode(t.r),
        RlpCodec[UInt256].encode(t.s)
      )

    case t: Blob =>
      Vector(
        RlpCodec[UInt64].encode(t.chainId),
        RlpCodec[UInt64].encode(t.nonce),
        RlpCodec[UInt256].encode(t.maxPriorityFeePerGas),
        RlpCodec[UInt256].encode(t.maxFeePerGas),
        RlpCodec[UInt64].encode(t.gasLimit),
        RlpCodec[Address].encode(t.recipient),
        RlpCodec[UInt256].encode(t.value),
        RlpCodec[Bytes].encode(t.data),
        RlpCodec[Seq[AccessTuple]].encode(t.accessList),
        RlpCodec[UInt256].encode(t.maxFeePerBlobGas),
        RlpCodec[Seq[Hash]].encode(t.blobVersionedHashes),
        RlpCodec[UInt256].encode(t.yParity),
        RlpCodec[UInt256].encode(t.r),
        RlpCodec[UInt256].encode(t.s)
      )

    case t: SetCode =>
      Vector(
        RlpCodec[UInt64].encode(t.chainId),
        RlpCodec[UInt64].encode(t.nonce),
        RlpCodec[UInt256].encode(t.maxPriorityFeePerGas),
        RlpCodec[UInt256].encode(t.maxFeePerGas),
        RlpCodec[UInt64].encode(t.gasLimit),
        RlpCodec[Address].encode(t.recipient),
        RlpCodec[UInt256].encode(t.value),
        RlpCodec[Bytes].encode(t.data),
        RlpCodec[Seq[AccessTuple]].encode(t.accessList),
        RlpCodec[Seq[Authorization]].encode(t.authorizationList),
        RlpCodec[UInt256].encode(t.yParity),
        RlpCodec[UInt256].encode(t.r),
        RlpCodec[UInt256].encode(t.s)
      )

  /** Contract creation is the EMPTY byte string, which is why this is not an
    * address codec applied to an option.
    *
    * An address of twenty zero bytes is a real account and encodes as twenty
    * bytes; absence encodes as none. Collapsing the two would send value to
    * the zero address instead of creating a contract.
    */
  private def encodeRecipient(to: Option[Address]): RlpItem = to match
    case Some(address) => RlpCodec[Address].encode(address)
    case None          => RlpItem.Bytes(IArray.empty[Byte])

  private def decodeRecipient(item: RlpItem): Either[RlpError, Option[Address]] = item match
    case RlpItem.Bytes(payload) if payload.isEmpty => Right(None)
    case other                                     => RlpCodec[Address].decode(other).map(Some.apply)

  private def decodeLegacyItem(item: RlpItem): Either[RlpError, Transaction] = item match
    case RlpItem.Bytes(_) => Left(RlpError.ExpectedSequence)
    case RlpItem.Sequence(items) =>
      if items.length != Legacy.FieldCount then
        Left(RlpError.WrongWidth(Legacy.FieldCount, items.length))
      else
        for
          nonce    <- RlpCodec[UInt64].decode(items(0))
          gasPrice <- RlpCodec[UInt256].decode(items(1))
          gasLimit <- RlpCodec[UInt64].decode(items(2))
          to       <- decodeRecipient(items(3))
          value    <- RlpCodec[UInt256].decode(items(4))
          data     <- RlpCodec[Bytes].decode(items(5))
          v        <- RlpCodec[UInt256].decode(items(6))
          r        <- RlpCodec[UInt256].decode(items(7))
          s        <- RlpCodec[UInt256].decode(items(8))
        yield Legacy(nonce, gasPrice, gasLimit, to, value, data, v, r, s)

  private def decodeTypedPayload(typeNumber: Int, item: RlpItem): Either[RlpError, Transaction] =
    item match
      case RlpItem.Bytes(_) => Left(RlpError.ExpectedSequence)
      case RlpItem.Sequence(items) =>
        typeNumber match
          case AccessList.TypeNumber => decodeAccessList(items)
          case DynamicFee.TypeNumber => decodeDynamicFee(items)
          case Blob.TypeNumber       => decodeBlob(items)
          case SetCode.TypeNumber    => decodeSetCode(items)
          case other                 => Left(RlpError.UnknownDiscriminant(other))

  private def decodeAccessList(items: Vector[RlpItem]): Either[RlpError, Transaction] =
    if items.length != AccessList.FieldCount then
      Left(RlpError.WrongWidth(AccessList.FieldCount, items.length))
    else
      for
        chainId  <- RlpCodec[UInt64].decode(items(0))
        nonce    <- RlpCodec[UInt64].decode(items(1))
        gasPrice <- RlpCodec[UInt256].decode(items(2))
        gasLimit <- RlpCodec[UInt64].decode(items(3))
        to       <- decodeRecipient(items(4))
        value    <- RlpCodec[UInt256].decode(items(5))
        data     <- RlpCodec[Bytes].decode(items(6))
        access   <- RlpCodec[Seq[AccessTuple]].decode(items(7))
        yParity  <- RlpCodec[UInt256].decode(items(8))
        r        <- RlpCodec[UInt256].decode(items(9))
        s        <- RlpCodec[UInt256].decode(items(10))
      yield AccessList(chainId, nonce, gasPrice, gasLimit, to, value, data, access, yParity, r, s)

  private def decodeDynamicFee(items: Vector[RlpItem]): Either[RlpError, Transaction] =
    if items.length != DynamicFee.FieldCount then
      Left(RlpError.WrongWidth(DynamicFee.FieldCount, items.length))
    else
      for
        chainId  <- RlpCodec[UInt64].decode(items(0))
        nonce    <- RlpCodec[UInt64].decode(items(1))
        tip      <- RlpCodec[UInt256].decode(items(2))
        cap      <- RlpCodec[UInt256].decode(items(3))
        gasLimit <- RlpCodec[UInt64].decode(items(4))
        to       <- decodeRecipient(items(5))
        value    <- RlpCodec[UInt256].decode(items(6))
        data     <- RlpCodec[Bytes].decode(items(7))
        access   <- RlpCodec[Seq[AccessTuple]].decode(items(8))
        yParity  <- RlpCodec[UInt256].decode(items(9))
        r        <- RlpCodec[UInt256].decode(items(10))
        s        <- RlpCodec[UInt256].decode(items(11))
      yield DynamicFee(chainId, nonce, tip, cap, gasLimit, to, value, data, access, yParity, r, s)

  private def decodeBlob(items: Vector[RlpItem]): Either[RlpError, Transaction] =
    if items.length != Blob.FieldCount then
      Left(RlpError.WrongWidth(Blob.FieldCount, items.length))
    else
      for
        chainId   <- RlpCodec[UInt64].decode(items(0))
        nonce     <- RlpCodec[UInt64].decode(items(1))
        tip       <- RlpCodec[UInt256].decode(items(2))
        cap       <- RlpCodec[UInt256].decode(items(3))
        gasLimit  <- RlpCodec[UInt64].decode(items(4))
        recipient <- RlpCodec[Address].decode(items(5))
        value     <- RlpCodec[UInt256].decode(items(6))
        data      <- RlpCodec[Bytes].decode(items(7))
        access    <- RlpCodec[Seq[AccessTuple]].decode(items(8))
        blobFee   <- RlpCodec[UInt256].decode(items(9))
        hashes    <- RlpCodec[Seq[Hash]].decode(items(10))
        yParity   <- RlpCodec[UInt256].decode(items(11))
        r         <- RlpCodec[UInt256].decode(items(12))
        s         <- RlpCodec[UInt256].decode(items(13))
      yield Blob(
        chainId,
        nonce,
        tip,
        cap,
        gasLimit,
        recipient,
        value,
        data,
        access,
        blobFee,
        hashes,
        yParity,
        r,
        s
      )

  private def decodeSetCode(items: Vector[RlpItem]): Either[RlpError, Transaction] =
    if items.length != SetCode.FieldCount then
      Left(RlpError.WrongWidth(SetCode.FieldCount, items.length))
    else
      for
        chainId   <- RlpCodec[UInt64].decode(items(0))
        nonce     <- RlpCodec[UInt64].decode(items(1))
        tip       <- RlpCodec[UInt256].decode(items(2))
        cap       <- RlpCodec[UInt256].decode(items(3))
        gasLimit  <- RlpCodec[UInt64].decode(items(4))
        recipient <- RlpCodec[Address].decode(items(5))
        value     <- RlpCodec[UInt256].decode(items(6))
        data      <- RlpCodec[Bytes].decode(items(7))
        access    <- RlpCodec[Seq[AccessTuple]].decode(items(8))
        auths     <- RlpCodec[Seq[Authorization]].decode(items(9))
        yParity   <- RlpCodec[UInt256].decode(items(10))
        r         <- RlpCodec[UInt256].decode(items(11))
        s         <- RlpCodec[UInt256].decode(items(12))
      yield SetCode(
        chainId,
        nonce,
        tip,
        cap,
        gasLimit,
        recipient,
        value,
        data,
        access,
        auths,
        yParity,
        r,
        s
      )

  /** The form a transaction takes as an ELEMENT OF A BLOCK BODY, which is the
    * only whole-value encoding of a transaction that round-trips.
    *
    * A legacy transaction is the list itself; a typed one is a byte string
    * holding [[canonicalBytes]]. The two are told apart on decode by the first
    * byte, and they cannot collide: an RLP list's own first byte is at least
    * `0xc0`, well above the `0x7f` EIP-2718 caps a type number at.
    *
    * The signing preimage is deliberately NOT here — it is encode-only, it
    * omits the signature, and since EIP-155 the preimage used for RECOVERY
    * depends on the `v` observed at run time. See [[SigningPreimage]].
    */
  given transactionCodec: RlpCodec[Transaction] with

    def encode(value: Transaction): RlpItem = value match
      case t: Legacy => RlpItem.Sequence(legacyFields(t))
      case other     => RlpItem.Bytes(canonicalBytes(other))

    def decode(item: RlpItem): Either[RlpError, Transaction] = item match
      case sequence: RlpItem.Sequence => decodeLegacyItem(sequence)
      case RlpItem.Bytes(payload) =>
        if payload.isEmpty then Left(RlpError.EmptyInput)
        else
          val head = payload(0) & 0xff
          if head > MaxTypeNumber then Left(RlpError.UnknownDiscriminant(head))
          else Rlp.decode(payload.drop(1)).flatMap(inner => decodeTypedPayload(head, inner))
