package org.fukuii.types

import org.fukuii.bytes.{Bytes, Hash, UInt64}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** The two header elements a consensus engine fills with its proof.
  *
  * The wire specification names them together — "the validity of the
  * proof-of-work seal (`mix-digest`, `block-nonce`)" — so `seal` is the
  * ecosystem's word for the pair rather than one chosen here.
  *
  * ==Why this is a sum and not two fields==
  *
  * The engine is pluggable, and the two elements are the only part of a header
  * that changes with it. Every other field means the same thing under every
  * engine; these two are **an alternative**, and both reference clients that
  * implement more than one engine say so in those words.
  *
  * A header therefore carries exactly one seal, and the arity is two either
  * way, so nothing else about the encoding moves.
  *
  * ==The representation improves on both clients, and their own source says why==
  *
  * One client keeps both seals as optional fields on a single header struct
  * and switches on whether the alternative one is populated. Its decoder has to
  * clear the other branch by hand on both paths, and its comment states the
  * consequence exactly: a reused header can carry stale fields from a previous
  * decode, and **the hash depends on which branch is set**. The other client
  * uses a subclass, so a header sealed by the alternative engine still inherits
  * the proof-of-work fields, present and meaningless, and copying a header
  * needs an override to avoid dropping the real seal.
  *
  * Both are the same hazard reached two ways, and both are mitigated by
  * discipline. A sum makes it unrepresentable instead: there is no second
  * branch to leave stale and no inherited field to mean nothing. **That is the
  * field's own semantics rather than a departure from it** — only the
  * representation differs, and it differs toward the thing their comments are
  * defending against.
  *
  * ==Which engine sealed a header is read from the octets, not configured==
  *
  * See [[Seal.fromFields]]. That is what the client running the largest
  * alternative-engine network does, and it is what keeps a single
  * [[org.fukuii.rlp.RlpCodec]] instance honest: one value, one encoding, chosen
  * by nothing at run time.
  */
enum Seal:

  /** The proof-of-work seal: a mixed hash and the nonce that satisfies it.
    *
    * @param mixHash
    *   the same slot a network that has replaced proof of work reads as its
    *   previous randomness value. The name is the one the conformance fixtures
    *   use at every fork including the most recent, so it is the encoding
    *   layer's name for the slot rather than either family's reading of it.
    */
  case Ethash(mixHash: Hash, nonce: BlockNonce)

  /** The authority-round seal: the step the block was sealed in, and the
    * sealer's signature.
    *
    * Named for the engine as the chain specifications name it, rather than for
    * the abbreviation both implementing clients use in code.
    *
    * @param signature
    *   empty until the block is sealed, which is a state one client encodes
    *   deliberately, so an empty signature is a shape rather than a defect.
    */
  case AuthorityRound(step: UInt64, signature: Bytes)

object Seal:

  /** How many header elements a seal occupies. The same under every engine,
    * which is why adding one moves nothing else in the encoding.
    */
  val FieldCount: Int = 2

  private[types] def fieldsOf(seal: Seal): Vector[RlpItem] = seal match
    case Ethash(mixHash, nonce) =>
      Vector(RlpCodec[Hash].encode(mixHash), RlpCodec[BlockNonce].encode(nonce))
    case AuthorityRound(step, signature) =>
      Vector(RlpCodec[UInt64].encode(step), RlpCodec[Bytes].encode(signature))

  /** Reads a seal from the two elements, discriminating on the width of the
    * first.
    *
    * ==Why width is sufficient, and why it is not a guess==
    *
    * A mixed hash is a fixed-width 32-byte value, so its element is always
    * exactly 32 bytes. An authority-round step is a scalar in the machine word,
    * so its element is at most eight and is minimally encoded. The two ranges
    * cannot overlap, so the pair is injective and the discrimination is exact
    * rather than heuristic.
    *
    * The client that runs the largest authority-round network decodes it this
    * way, from the bytes, with no chain configuration involved — its branch is
    * literally on whether the first element's size is 32.
    *
    * ==Reading a seal is not admitting one==
    *
    * A node whose engine is proof of work will decode an authority-round header
    * here rather than refuse it. That is the same boundary every other type in
    * this module holds: which engine is correct at a given height on a given
    * network is a fork rule the layer above owns, and a decoder that could not
    * read the header could not tell a malformed one from a well-formed one it
    * does not accept — which are different answers to the peer that sent it.
    */
  private[types] def fromFields(first: RlpItem, second: RlpItem): Either[RlpError, Seal] =
    first match
      case RlpItem.Bytes(payload) if payload.length == Hash.Width =>
        for
          mixHash <- RlpCodec[Hash].decode(first)
          nonce   <- RlpCodec[BlockNonce].decode(second)
        yield Ethash(mixHash, nonce)
      case _: RlpItem.Bytes =>
        for
          step      <- RlpCodec[UInt64].decode(first)
          signature <- RlpCodec[Bytes].decode(second)
        yield AuthorityRound(step, signature)
      case _: RlpItem.Sequence => Left(RlpError.ExpectedBytes)
