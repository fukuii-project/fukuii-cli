package org.fukuii.rlp

import org.fukuii.bytes.{Address, Hash, UInt256}

/** A total, round-trippable mapping between a value and its RLP form.
  *
  * ==The boundary, and it is the whole contract==
  *
  * An instance exists only for a **whole-value encoding that round-trips**.
  * Both directions are meaningful for every instance, and `decode` of `encode`
  * is the identity.
  *
  * That excludes a real and important case: a **signing preimage** is not a
  * codec. It is encode-only, it is a projection rather than the whole value —
  * a transaction's is taken over its fields with the signature omitted — and
  * which projection applies is chosen by fork rules and, on recovery, by
  * inspecting a value at run time. None of that can be a compile-time instance,
  * and the two reference implementations agree: neither models a signing
  * preimage as a codec. Such a function lives with the type it projects, never
  * here.
  *
  * ==One instance per type, and several encodings per type==
  *
  * A type may legitimately have more than one encoding — a receipt has a
  * consensus form and a storage form, a transaction has a block-body form and a
  * pooled form. **Each gets its own TYPE, not a flag on one instance.** A
  * distinct type takes a distinct instance, resolution stays at compile time,
  * and a missing instance stays a compile error.
  *
  * The alternative — one instance selected by a runtime key or a configuration
  * object — is what makes "the same value, a different encoder" possible
  * without the compiler seeing it, and that failure has already cost this
  * project once.
  */
trait RlpCodec[A]:
  def encode(value: A): RlpItem
  def decode(item: RlpItem): Either[RlpError, A]

object RlpCodec:

  def apply[A](using codec: RlpCodec[A]): RlpCodec[A] = codec

  /** Encodes straight to bytes. The composition of this with [[decodeFrom]] is
    * where a decode budget attaches when one is chosen — see `Rlp.decode`.
    */
  def encodeTo[A](value: A)(using codec: RlpCodec[A]): IArray[Byte] =
    Rlp.encode(codec.encode(value))

  def decodeFrom[A](bytes: IArray[Byte])(using codec: RlpCodec[A]): Either[RlpError, A] =
    Rlp.decode(bytes).flatMap(codec.decode)

  /** A byte string, carried as-is.
    *
    * **There is deliberately no `RlpCodec[Byte]`, and its absence is the
    * mechanism rather than an omission.** Without one, `RlpCodec[Seq[Byte]]`
    * cannot be summoned, so a byte sequence has exactly one encoding reachable
    * from the type system: this leaf. Were both to exist, the same bytes would
    * encode as a single leaf through one and as a list of one-byte leaves
    * through the other — two encodings of one value, chosen silently by static
    * type. `RlpCodecSpec` pins the absence.
    */
  given bytesCodec: RlpCodec[IArray[Byte]] with
    def encode(value: IArray[Byte]): RlpItem = RlpItem.Bytes(value)
    def decode(item: RlpItem): Either[RlpError, IArray[Byte]] = item match
      case RlpItem.Bytes(value) => Right(value)
      case _: RlpItem.Sequence  => Left(RlpError.ExpectedBytes)

  /** A quantity, under the Yellow Paper's scalar rule: minimal big-endian, and
    * the empty string for zero.
    *
    * Decoding rejects any leading zero byte, including a lone `0x00`. That is a
    * structurally valid RLP byte string and it is not a canonical scalar — zero
    * is the empty string — so accepting it would admit a second encoding of a
    * value that must have exactly one.
    */
  given uint256Codec: RlpCodec[UInt256] with
    def encode(value: UInt256): RlpItem = RlpItem.Bytes(value.toMinimalBytes)
    def decode(item: RlpItem): Either[RlpError, UInt256] = item match
      case RlpItem.Bytes(payload) =>
        if payload.nonEmpty && payload(0) == 0 then Left(RlpError.NonCanonicalScalar)
        else if payload.length > UInt256.Width then Left(RlpError.WrongWidth(UInt256.Width, payload.length))
        else UInt256.fromBytes(payload).left.map(_ => RlpError.WrongWidth(UInt256.Width, payload.length))
      case _: RlpItem.Sequence => Left(RlpError.ExpectedBytes)

  /** A machine-word quantity — a block number, a gas figure, a timestamp.
    *
    * The same scalar rule as [[uint256Codec]], bounded at eight bytes. Negative
    * values cannot be encoded: the scalar rule has no representation for one,
    * and a caller holding a negative here has a defect this cannot express.
    */
  given longCodec: RlpCodec[Long] with
    private val Width = 8

    def encode(value: Long): RlpItem =
      require(value >= 0, "a scalar has no encoding for a negative value")
      RlpItem.Bytes(minimal(value))

    def decode(item: RlpItem): Either[RlpError, Long] = item match
      case RlpItem.Bytes(payload) =>
        if payload.nonEmpty && payload(0) == 0 then Left(RlpError.NonCanonicalScalar)
        else if payload.length > Width then Left(RlpError.WrongWidth(Width, payload.length))
        else
          val value = payload.foldLeft(0L)((acc, b) => (acc << 8) | (b & 0xffL))
          // The protocol's machine word is UNSIGNED and this one is not. Eight
          // bytes with the high bit set is a legitimate uint64 and a negative
          // Long, which would round-trip back through `encode`'s non-negative
          // requirement and throw. Rejecting is the honest answer: the value is
          // representable in the protocol and not in this type.
          if value < 0 then Left(RlpError.ScalarOutOfRange) else Right(value)
      case _: RlpItem.Sequence => Left(RlpError.ExpectedBytes)

    private def minimal(value: Long): IArray[Byte] =
      if value == 0 then IArray.empty[Byte]
      else
        var width = 8
        while width > 1 && ((value >>> ((width - 1) * 8)) & 0xffL) == 0 do width -= 1
        IArray.from((0 until width).map(i => ((value >>> ((width - 1 - i) * 8)) & 0xffL).toByte))

  /** A fixed-width value is NOT a scalar: its leading zeros are part of it.
    *
    * A hash of `0x00ab…` is a different hash from `0xab…`, so the width is
    * exact in both directions and a short input is an error rather than
    * something to left-pad.
    */
  given hashCodec: RlpCodec[Hash] with
    def encode(value: Hash): RlpItem = RlpItem.Bytes(value.toBytes)
    def decode(item: RlpItem): Either[RlpError, Hash] = item match
      case RlpItem.Bytes(payload) =>
        Hash.fromBytes(payload).left.map(_ => RlpError.WrongWidth(Hash.Width, payload.length))
      case _: RlpItem.Sequence => Left(RlpError.ExpectedBytes)

  given addressCodec: RlpCodec[Address] with
    def encode(value: Address): RlpItem = RlpItem.Bytes(value.toBytes)
    def decode(item: RlpItem): Either[RlpError, Address] = item match
      case RlpItem.Bytes(payload) =>
        Address.fromBytes(payload).left.map(_ => RlpError.WrongWidth(Address.Width, payload.length))
      case _: RlpItem.Sequence => Left(RlpError.ExpectedBytes)

  /** A sequence, element-wise.
    *
    * `Seq` rather than a narrower collection so a caller is not forced to
    * convert; the decoded side is a `Vector`, which is what the item model
    * already holds.
    */
  given seqCodec[A](using element: RlpCodec[A]): RlpCodec[Seq[A]] with
    def encode(value: Seq[A]): RlpItem = RlpItem.Sequence(value.map(element.encode).toVector)
    def decode(item: RlpItem): Either[RlpError, Seq[A]] = item match
      case RlpItem.Sequence(items) =>
        items.foldLeft[Either[RlpError, Vector[A]]](Right(Vector.empty)) { (acc, next) =>
          acc.flatMap(soFar => element.decode(next).map(soFar :+ _))
        }
      case _: RlpItem.Bytes => Left(RlpError.ExpectedSequence)
