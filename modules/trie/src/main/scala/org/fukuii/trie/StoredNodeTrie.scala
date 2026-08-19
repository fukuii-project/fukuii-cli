package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hash}
import org.fukuii.storage.{KeyValueStore, LeafIterator, Namespace}

import scala.collection.mutable

/** A [[Trie]] whose nodes are addressable objects: every node whose encoding
  * reaches [[TrieNode.InlineLimit]] is written under its own digest, and the
  * tree is edited in place rather than rebuilt.
  *
  * ==Why this exists beside [[DerivedNodeTrie]]==
  *
  * The two are structurally opposed on purpose. This one requires that a node be
  * fetchable by digest and maintains the tree incrementally; the other has no
  * nodes to fetch and derives the tree from the entry set. A seam with one
  * implementation is indistinguishable from that implementation's interface, and
  * the commitments produced by two implementations that share no tree-building
  * code are worth comparing — a shared helper would make them agree for reasons
  * that say nothing about either.
  *
  * Read that as the narrow claim it is. Both still route through [[TrieNode]]'s
  * encoding, inline rule and root rule, so their agreement is evidence about
  * tree topology and about nothing below it. A defect shared by both would be
  * caught by the vectors checked against externally published roots, never by
  * comparing these two to each other.
  *
  * ==The node keyspace is content-addressed and therefore unversioned==
  *
  * A node's digest is a function of its contents, so two snapshots that share a
  * subtree share its nodes and a version would distinguish nothing. That is why
  * this uses the store's unversioned operations where [[DerivedNodeTrie]] uses
  * the versioned ones, and it is the same split the field takes.
  *
  * ==And why [[leaves]] is a walk rather than a lookup==
  *
  * Nothing in a node keyspace is ordered by trie key — it is ordered by digest,
  * which is an unrelated order — so the ordered leaf view has to come from
  * traversing the tree. That cost is a property of the shape rather than of this
  * implementation, and it is why a node-addressed client that wants to serve
  * ordered ranges cheaply builds a second structure alongside its trie.
  *
  * ==Failure==
  *
  * A digest this trie wrote that the store cannot return, or returns
  * undecodably, is a corrupt store rather than a domain outcome, and both throw
  * `IllegalStateException`. A legitimately absent node — a partially healed or
  * pruned state — is a different case with a different answer, and nothing in
  * this phase produces one.
  *
  * ==Concurrency==
  *
  * Not thread-safe. A single instance is not meant to be shared across threads.
  */
final class StoredNodeTrie(
    val securing: Securing,
    store: KeyValueStore,
    nodes: Namespace.Standalone
) extends Trie:

  private var rootRef: NodeRef = NodeRef.Empty

  def root: Hash = TrieNode.rootHash(rootRef)

  def get(key: Bytes): Option[Bytes] = lookup(rootRef, pathOf(key))

  def put(key: Bytes, value: Bytes): Unit =
    if value.isEmpty then delete(key)
    else rootRef = persist(insert(resolve(rootRef), pathOf(key), value))

  def delete(key: Bytes): Unit =
    rootRef = remove(resolve(rootRef), pathOf(key)) match
      case None       => NodeRef.Empty
      case Some(node) => persist(node)

  def leaves: LeafIterator =
    val collected = Vector.newBuilder[(Bytes, Bytes)]
    collect(rootRef, Nibbles.Empty, collected)
    new WalkIterator(collected.result())

  private def pathOf(key: Bytes): Nibbles = Nibbles.fromBytes(Trie.trieKey(securing, key).toIArray)

  private def emptyBranch: BranchNode = BranchNode(TrieNode.EmptyChildren, Bytes.Empty)

  // ── Node access ──────────────────────────────────────────────────────────

  private def load(hash: Hash): TrieNode =
    store.get(nodes, Bytes.fromIArray(hash.toBytes)) match
      case None =>
        throw new IllegalStateException("node store holds no node under the digest " + hash.toHex)
      case Some(encoded) =>
        TrieNode.decode(encoded.toIArray) match
          case Right(node) => node
          case Left(_)     =>
            throw new IllegalStateException("node store holds an undecodable node under the digest " + hash.toHex)

  /** The node behind a reference known to name one. */
  private def child(ref: NodeRef): TrieNode = ref match
    case NodeRef.Empty =>
      throw new IllegalStateException("a child slot recorded as occupied names no node")
    case NodeRef.Inline(node) => node
    case NodeRef.Hashed(hash) => load(hash)

  private def resolve(ref: NodeRef): Option[TrieNode] = ref match
    case NodeRef.Empty => None
    case named         => Some(child(named))

  /** Names `node` the way a parent will, writing it under its digest when the
    * inline rule does not embed it. An embedded node needs no write: it travels
    * inside whatever parent references it.
    */
  private def persist(node: TrieNode): NodeRef =
    TrieNode.cap(Some(node)) match
      case NodeRef.Hashed(hash) =>
        store.update(
          nodes,
          Nil,
          Seq(Bytes.fromIArray(hash.toBytes) -> Bytes.fromIArray(TrieNode.encode(node)))
        )
        NodeRef.Hashed(hash)
      case embedded => embedded

  // ── Reading ──────────────────────────────────────────────────────────────

  private def lookup(ref: NodeRef, key: Nibbles): Option[Bytes] = resolve(ref) match
    case None                             => None
    case Some(LeafNode(restOfKey, value)) =>
      if restOfKey == key then Some(value) else None
    case Some(ExtensionNode(keySegment, subnode)) =>
      if key.commonPrefixLength(keySegment) == keySegment.length then lookup(subnode, key.drop(keySegment.length))
      else None
    case Some(BranchNode(subnodes, value)) =>
      if key.isEmpty then Option.when(value.nonEmpty)(value)
      else lookup(subnodes(key(0)), key.drop(1))

  // ── Insertion ────────────────────────────────────────────────────────────

  /** The subtree that results from storing `value` at `key` in the subtree
    * `node`, whose ancestors have already consumed everything before `key`.
    *
    * Every branch below preserves the shape the definition would have built for
    * the same contents: no extension is created with an empty segment, no
    * extension is created directly above another, and a branch is created only
    * where two keys genuinely diverge. Those are not tidiness — a trie that is a
    * different shape is a different commitment.
    */
  private def insert(node: Option[TrieNode], key: Nibbles, value: Bytes): TrieNode = node match
    case None => LeafNode(key, value)

    case Some(LeafNode(restOfKey, existing)) =>
      if restOfKey == key then LeafNode(key, value)
      else
        val shared = restOfKey.commonPrefixLength(key)
        val branch = place(place(emptyBranch, restOfKey.drop(shared), existing), key.drop(shared), value)
        if shared == 0 then branch else ExtensionNode(key.take(shared), persist(branch))

    case Some(ExtensionNode(keySegment, subnode)) =>
      val shared = keySegment.commonPrefixLength(key)
      if shared == keySegment.length then
        ExtensionNode(keySegment, persist(insert(resolve(subnode), key.drop(shared), value)))
      else
        val remainder = keySegment.drop(shared)
        val demoted =
          if remainder.length == 1 then subnode
          else persist(ExtensionNode(remainder.drop(1), subnode))
        val carried = BranchNode(TrieNode.EmptyChildren.updated(remainder(0), demoted), Bytes.Empty)
        val branch = place(carried, key.drop(shared), value)
        if shared == 0 then branch else ExtensionNode(keySegment.take(shared), persist(branch))

    case Some(BranchNode(subnodes, existing)) =>
      if key.isEmpty then BranchNode(subnodes, value)
      else
        val index = key(0)
        BranchNode(subnodes.updated(index, persist(insert(resolve(subnodes(index)), key.drop(1), value))), existing)

  /** Puts `value` into `branch` at `suffix`: into the branch's own value slot
    * when the suffix is exhausted, and otherwise into the child the suffix's
    * first nibble selects.
    */
  private def place(branch: BranchNode, suffix: Nibbles, value: Bytes): BranchNode =
    if suffix.isEmpty then BranchNode(branch.subnodes, value)
    else BranchNode(branch.subnodes.updated(suffix(0), persist(LeafNode(suffix.drop(1), value))), branch.value)

  // ── Removal ──────────────────────────────────────────────────────────────

  private def remove(node: Option[TrieNode], key: Nibbles): Option[TrieNode] = node match
    case None => None

    case Some(leaf: LeafNode) =>
      if leaf.restOfKey == key then None else Some(leaf)

    case Some(extension: ExtensionNode) =>
      if key.commonPrefixLength(extension.keySegment) != extension.keySegment.length then Some(extension)
      else
        remove(resolve(extension.subnode), key.drop(extension.keySegment.length)) match
          case None                             => None
          case Some(LeafNode(restOfKey, value)) =>
            Some(LeafNode(extension.keySegment ++ restOfKey, value))
          case Some(ExtensionNode(inner, subnode)) =>
            Some(ExtensionNode(extension.keySegment ++ inner, subnode))
          case Some(branch: BranchNode) =>
            Some(ExtensionNode(extension.keySegment, persist(branch)))

    case Some(BranchNode(subnodes, value)) =>
      if key.isEmpty then
        if value.isEmpty then Some(BranchNode(subnodes, value))
        else collapse(BranchNode(subnodes, Bytes.Empty))
      else
        val index = key(0)
        val replaced = remove(resolve(subnodes(index)), key.drop(1)) match
          case None       => NodeRef.Empty
          case Some(next) => persist(next)
        collapse(BranchNode(subnodes.updated(index, replaced), value))

  /** Reduces a branch that a removal has left with fewer than two entries.
    *
    * A branch exists to hold a divergence, so one holding a single entry is not
    * a shape the definition builds and leaving it would produce a commitment no
    * other implementation agrees with. Merging the surviving entry back into the
    * nibble that selected it is what restores the shape.
    */
  private def collapse(branch: BranchNode): Option[TrieNode] =
    val occupied = branch.subnodes.indices.filter(index => branch.subnodes(index) != NodeRef.Empty)
    if occupied.length > 1 || (occupied.length == 1 && branch.value.nonEmpty) then Some(branch)
    else if occupied.isEmpty then Option.when(branch.value.nonEmpty)(LeafNode(Nibbles.Empty, branch.value))
    else
      val consumed = Nibbles.single(occupied.head)
      child(branch.subnodes(occupied.head)) match
        case LeafNode(restOfKey, value)    => Some(LeafNode(consumed ++ restOfKey, value))
        case ExtensionNode(inner, subnode) => Some(ExtensionNode(consumed ++ inner, subnode))
        case surviving: BranchNode         => Some(ExtensionNode(consumed, persist(surviving)))

  // ── Ordered traversal ────────────────────────────────────────────────────

  private def collect(
      ref: NodeRef,
      prefix: Nibbles,
      out: mutable.Builder[(Bytes, Bytes), Vector[(Bytes, Bytes)]]
  ): Unit =
    resolve(ref) match
      case None                                     => ()
      case Some(LeafNode(restOfKey, value))         => emit(out, prefix ++ restOfKey, value)
      case Some(ExtensionNode(keySegment, subnode)) => collect(subnode, prefix ++ keySegment, out)
      case Some(BranchNode(subnodes, value))        =>
        // A key ending here is a prefix of every key below it, and a prefix
        // sorts before what extends it, so the branch's own value is emitted
        // before its children rather than after.
        if value.nonEmpty then emit(out, prefix, value)
        var index = 0
        while index < TrieNode.ChildCount do
          collect(subnodes(index), prefix ++ Nibbles.single(index), out)
          index += 1

  private def emit(
      out: mutable.Builder[(Bytes, Bytes), Vector[(Bytes, Bytes)]],
      path: Nibbles,
      value: Bytes
  ): Unit =
    path.toBytes match
      case Some(key) => val _ = out.addOne(key -> value)
      case None      =>
        throw new IllegalStateException("a root-to-leaf path holds an odd number of nibbles")

  final private class WalkIterator(entries: Vector[(Bytes, Bytes)]) extends LeafIterator:
    private var position: Int = 0
    private var closedFlag: Boolean = false

    def hasNext: Boolean = position < entries.length

    def next(): (Bytes, Bytes) =
      if !hasNext then throw new NoSuchElementException("next on an exhausted LeafIterator")
      val entry = entries(position)
      position += 1
      entry

    def isClosed: Boolean = closedFlag

    def close(): Unit = closedFlag = true
