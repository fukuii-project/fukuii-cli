package org.fukuii.storage

import org.fukuii.bytes.Hash

/** The identity of a single, unique-per-block snapshot of a versioned
  * namespace.
  *
  * An opaque wrapper over [[org.fukuii.bytes.Hash]] rather than a block
  * number: two sibling blocks at the same height must key distinct
  * snapshots, which only a value unique per block guarantees. It is also,
  * deliberately, not the state root that a snapshot commits to — a state
  * root can repeat across two distinct blocks (an empty block on a chain
  * with no per-block execution-layer write, or two sibling blocks sharing a
  * parent, a coinbase and no transactions), so keying a snapshot by it can
  * silently merge two blocks' state. A block hash has neither failure mode.
  *
  * A root remains a legitimate way to ADDRESS state for reading — "give me
  * the state as of root R" is a question a root answers unambiguously. This
  * type is for the different use of KEYING a snapshot internally, where a
  * root is the wrong key because two distinct blocks can share one; that
  * disambiguation is `modules/trie`'s to apply once it addresses state by
  * root, not this module's.
  */
opaque type Version = Hash

object Version:
  def apply(blockHash: Hash): Version = blockHash
  extension (v: Version) def blockHash: Hash = v
