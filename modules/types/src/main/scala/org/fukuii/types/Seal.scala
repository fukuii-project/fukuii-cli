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

  /** The two-slot seal: a 32-byte digest, and a fixed-width nonce.
    *
    * Named for the slots rather than for an engine, because several engines
    * write this shape and disagree about what the slots hold. A proof-of-work
    * network puts a mixed hash and the nonce satisfying it here; a network that
    * has replaced proof of work reads the first slot as its previous randomness
    * value and writes a zero nonce; an authority engine that reuses the pair
    * writes a zero digest and a marker nonce. An engine name on this case would
    * therefore be false of every header but one family's, and the client running
    * the largest alternative-engine network names the pair by its slots for the
    * same reason.
    *
    * @param mixHash
    *   the slot's encoding-layer name, which is the one the conformance
    *   fixtures use at every fork including the most recent — deliberately not
    *   either family's reading of it.
    */
  case MixHashAndNonce(mixHash: Hash, nonce: BlockNonce)

  /** The authority-round seal: the step the block was sealed in, and the
    * sealer's signature.
    *
    * Named for the engine as the chain specifications name it, rather than for
    * the abbreviation both implementing clients use in code. **The asymmetry
    * against the case above is deliberate and is not a naming inconsistency to
    * repair:** this shape belongs to one engine, so an engine name describes it
    * exactly, while the two-slot shape is shared and an engine name there would
    * not.
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
    case MixHashAndNonce(mixHash, nonce) =>
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
    *
    * ==So the layer above owes a positive rejection, not merely a non-reading==
    *
    * Widening this to a sum moved a refusal out of the decoder: a first element
    * of eight canonical bytes or fewer used to fail to decode at all, and now
    * decodes and round-trips. **A network whose engine writes the two-slot seal
    * must therefore REJECT the other case rather than decline to interpret it**,
    * because nothing here does that any more. An exhaustive match over this sum
    * is what surfaces the obligation at the point it has to be met; a catch-all
    * arm silently discharges it.
    */
  private[types] def fromFields(first: RlpItem, second: RlpItem): Either[RlpError, Seal] =
    first match
      case RlpItem.Bytes(payload) if payload.length == Hash.Width =>
        for
          mixHash <- RlpCodec[Hash].decode(first)
          nonce   <- RlpCodec[BlockNonce].decode(second)
        yield MixHashAndNonce(mixHash, nonce)
      case _: RlpItem.Bytes =>
        for
          step      <- RlpCodec[UInt64].decode(first)
          signature <- RlpCodec[Bytes].decode(second)
        yield AuthorityRound(step, signature)
      case _: RlpItem.Sequence => Left(RlpError.ExpectedBytes)
