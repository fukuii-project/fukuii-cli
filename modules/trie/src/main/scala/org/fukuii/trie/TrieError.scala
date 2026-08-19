package org.fukuii.trie

import org.fukuii.rlp.RlpError

/** A reason bytes are not a well-formed Merkle-Patricia node, a nibble sequence
  * is not a well-formed hex-prefix encoding, or a state-trie leaf is not an
  * account.
  *
  * ==Why this is not routed through `RlpError`==
  *
  * Node encoding is a whole-value, round-trippable RLP mapping, so it looks like
  * an [[org.fukuii.rlp.RlpCodec]] instance. It is not one, and the error channel
  * is the reason: [[org.fukuii.rlp.RlpError]] names RLP-structural and RLP-typed
  * failures only, and every case below that is not [[MalformedNodeRlp]] is a
  * trie-shape failure. Expressing them there would teach `modules/rlp` what a
  * node is, which is the same boundary [[org.fukuii.rlp.RlpError]]'s own
  * `UnknownDiscriminant` is drawn to keep.
  */
enum TrieError:

  /** A hex-prefix path with no bytes at all. Every encoding carries at least the
    * flag byte, so an empty input is truncation rather than an empty path.
    */
  case EmptyCompactPath

  /** The two high bits of the flag nibble are unused and a canonical encoder
    * emits them as zero, so a flag above 3 is not an encoding this trie
    * produces.
    */
  case UnknownHexPrefixFlag(flag: Int)

  /** An even-length path whose flag byte carries a non-zero low nibble. The
    * Yellow Paper fixes that nibble at zero for the even case, so a non-zero
    * value is a second spelling of the same path.
    */
  case NonZeroPaddingNibble(value: Int)

  /** A nibble outside `0` to `15` inclusive. */
  case NibbleOutOfRange(value: Int)

  /** A root-to-leaf path with an odd nibble count, reached where whole bytes
    * were required. Every trie key is a byte string, so this reports a trie
    * built by some other route.
    */
  case OddNibbleCount(count: Int)

  /** An RLP item that is a byte string where a node's list was required, or a
    * list where one of a node's byte-string fields was required.
    */
  case NotANodeStructure

  /** A node list that is neither the two items of a leaf or extension nor the
    * seventeen of a branch.
    */
  case WrongNodeArity(actual: Int)

  /** A child slot holding a byte string that is neither empty nor a 32-byte
    * hash. An embedded child is a list, so no other width is a reference.
    */
  case InvalidChildReference(width: Int)

  /** An extension node consuming no nibbles. No conforming encoder emits one —
    * an extension exists to consume a shared prefix, and a zero-length prefix is
    * a branch — and accepting one would admit a node the traversals cannot make
    * progress through: descending it consumes nothing, so a chain of them does
    * not terminate.
    *
    * Every surveyed client accepts this shape, so refusing it is a deliberate
    * departure. It is safe against history because no conforming writer can have
    * produced such a node, and it cannot change a root for the same reason.
    */
  case EmptyExtensionSegment

  /** An extension node whose single child is the empty reference. The empty
    * reference means "no subtree", which is meaningful for one of a branch's
    * sixteen slots and meaningless for the one child an extension exists to
    * point at — [[TrieNode.cap]] returns it only for an absent node, so no
    * conforming encoder can have produced this shape.
    *
    * Admitting it would change a root rather than merely admit a curiosity:
    * inserting through such a node builds an extension above a leaf where the
    * trie's definition gives one merged leaf.
    *
    * ==The field is split here rather than unanimous==
    *
    * Refusing is a choice, so the survey it rests on is named rather than
    * summarized. Each was read from that project's source at the revision
    * given, because a decoder's behavior is not recoverable from its
    * documentation:
    *
    *   - '''besu''' @ `c2addd9424` refuses it, and structurally rather than by
    *     an explicit check: `StoredNodeFactory.decodeBranch` carries a
    *     null-slot case, `decodeExtension` carries none, and the child falls
    *     to a fixed-width read that rejects a zero-length payload.
    *     '''besu-etc''' @ `eb4248c997` is identical.
    *   - '''go-ethereum''' @ `6bb0588ad` accepts it: one `decodeRef` in
    *     `trie/node.go` serves both the single-child and the sixteen-slot
    *     position. '''core-geth''' @ `4185df450` and '''erigon''' @
    *     `7125aa1e84` inherit that shape.
    *   - '''nethermind''' @ `c35ce1b1ab`, '''ethrex''' @ `2367dc810` and
    *     '''alloy-trie''' @ `v0.9.4` accept it too, and each reaches that
    *     answer through its own mechanism rather than by inheriting one.
    *   - '''execution-specs''' @ `ccaaaba58` has no trie-node decoder at all,
    *     so the executable specification does not decide this one.
    *
    * '''So the field is one refusal against six acceptances, and permissiveness
    * is convergent rather than inherited.''' Three of the six are independent
    * implementations that arrived at it separately, which makes this a wider
    * departure than a decoder-sharing story would suggest, and is the reason
    * the survey is recorded here rather than summarized.
    *
    * '''What decides it is not the count.''' Two sources establish that no
    * conforming writer can emit the shape: the executable specification's
    * `patricialize` reaches its extension branch only with a non-empty entry
    * set, so the position can never receive an absent subtree; and go-ethereum,
    * which accepts the shape on decode, marks the corresponding branch of its
    * own extension encoder ''theoretically impossible to happen''. A client
    * that accepts a shape it cannot write is evidence about its decoder, not
    * about the shape.
    *
    * The choice is therefore the same one [[EmptyExtensionSegment]] and
    * [[OversizedInlineNode]] already make: safe against history because no
    * conforming writer emits the shape, and unable to change a root for the
    * same reason. Splitting the decode by position rather than adding a flag
    * is what makes the distinction expressible at all — a decoder that cannot
    * see its caller cannot enforce a caller-dependent rule.
    */
  case EmptyExtensionChild

  /** A child embedded in its parent whose own encoding reaches the inline
    * limit. Such a node must be referenced by hash, so the parent is not the
    * encoding any conforming implementation would have produced for its
    * contents.
    */
  case OversizedInlineNode(width: Int)

  /** The bytes are not RLP at all. */
  case MalformedNodeRlp(cause: RlpError)

  /** A state-trie leaf that is not an account encoding. */
  case MalformedAccount(cause: RlpError)
