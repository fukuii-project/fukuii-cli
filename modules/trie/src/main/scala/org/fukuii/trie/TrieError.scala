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
