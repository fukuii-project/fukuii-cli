package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hash}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.{Rlp, RlpItem}

/** One of the three kinds of node a Merkle-Patricia trie is built from.
  *
  * A `sealed trait` with `final case class` cases rather than an `enum`, for the
  * reason [[org.fukuii.rlp.RlpItem]] is one: these are constructed and matched
  * everywhere in this module, and an enum case constructor application widens to
  * the enum type wherever a more specific type is not expected, which turns
  * every un-ascribed intermediate binding into a latent conformance error.
  */
sealed trait TrieNode

/** Terminates a path and holds a value.
  *
  * @param restOfKey
  *   the nibbles of the key not consumed by any ancestor. May be empty, for a
  *   key that ends exactly where its parent branch begins.
  */
final case class LeafNode(restOfKey: Nibbles, value: Bytes) extends TrieNode

/** Consumes a run of nibbles shared by every key descending through it, so that
  * a long common prefix costs one node instead of a chain of single-child
  * branches.
  */
final case class ExtensionNode(keySegment: Nibbles, subnode: NodeRef) extends TrieNode

/** Sixteen children, one per nibble value, plus a value for a key terminating
  * exactly here.
  *
  * @param value
  *   empty when no key terminates at this node.
  */
final case class BranchNode(subnodes: Vector[NodeRef], value: Bytes) extends TrieNode:
  require(
    subnodes.length == TrieNode.ChildCount,
    "a branch node has one child slot per nibble value"
  )

/** How a parent names a child.
  *
  * The distinction is the trie's storage-complexity rule and it is
  * consensus-critical: a child whose own encoding is shorter than
  * [[TrieNode.InlineLimit]] is embedded in its parent whole, and a longer one is
  * replaced by the keccak-256 digest of that encoding. The two are
  * distinguishable in the encoded parent without ambiguity, because an embedded
  * child is a list and a digest is a 32-byte string.
  */
enum NodeRef:
  case Empty
  case Inline(node: TrieNode)
  case Hashed(hash: Hash)

object TrieNode:

  /** One child slot per nibble value. */
  val ChildCount: Int = 16

  /** A branch encodes as its children followed by its own value. */
  val BranchArity: Int = ChildCount + 1

  /** A node encoding shorter than this is embedded in its parent rather than
    * referenced by digest. Equal to the digest width, which is what makes the
    * rule a saving rather than a cost.
    */
  val InlineLimit: Int = Hash.Width

  val EmptyChildren: Vector[NodeRef] = Vector.fill(ChildCount)(NodeRef.Empty)

  def toRlp(node: TrieNode): RlpItem = node match
    case LeafNode(restOfKey, value) =>
      RlpItem.Sequence(Vector(RlpItem.Bytes(restOfKey.toCompact(true)), RlpItem.Bytes(value.toIArray)))
    case ExtensionNode(keySegment, subnode) =>
      RlpItem.Sequence(Vector(RlpItem.Bytes(keySegment.toCompact(false)), refToRlp(subnode)))
    case BranchNode(subnodes, value) =>
      RlpItem.Sequence(subnodes.map(refToRlp) :+ RlpItem.Bytes(value.toIArray))

  def refToRlp(ref: NodeRef): RlpItem = ref match
    case NodeRef.Empty        => RlpItem.Bytes(IArray.empty[Byte])
    case NodeRef.Inline(node) => toRlp(node)
    case NodeRef.Hashed(hash) => RlpItem.Bytes(hash.toBytes)

  def encode(node: TrieNode): IArray[Byte] = Rlp.encode(toRlp(node))

  /** How a parent names `node`: embedded when its encoding is shorter than
    * [[InlineLimit]], and the digest of that encoding otherwise. An absent
    * subtree is named by the empty byte string.
    */
  def cap(node: Option[TrieNode]): NodeRef = node match
    case None          => NodeRef.Empty
    case Some(present) =>
      val encoded = encode(present)
      if encoded.length < InlineLimit then NodeRef.Inline(present)
      else NodeRef.Hashed(Keccak256.hash(encoded))

  /** The 32-byte commitment for a trie whose root node is named by `ref`.
    *
    * ==The root node is hashed even when the inline rule would embed it==
    *
    * A short node anywhere else in the trie is embedded in its parent and never
    * hashed. The root has no parent, so the rule that would embed it has nothing
    * to embed it into, and the commitment is the digest of its encoding
    * regardless of width. That is why this cannot be written as "return the
    * digest the cap already computed".
    *
    * The two branches below are exactly the specification's single `if`: [[cap]]
    * returns [[NodeRef.Hashed]] precisely when the node's encoding reached
    * [[InlineLimit]], in which case re-encoding that digest as an RLP string
    * yields 33 bytes and the specification takes its own else-branch and returns
    * the digest unchanged. Every other case re-encodes to fewer than
    * [[InlineLimit]] bytes and is hashed here.
    *
    * An empty trie falls out of this rather than being special-cased: the empty
    * reference encodes as `0x80`, and its digest is the value every header
    * carries for a trie with no entries.
    */
  def rootHash(ref: NodeRef): Hash = ref match
    case NodeRef.Hashed(hash) => hash
    case other                => Keccak256.hash(Rlp.encode(refToRlp(other)))

  def decode(bytes: IArray[Byte]): Either[TrieError, TrieNode] =
    Rlp.decode(bytes).left.map(TrieError.MalformedNodeRlp.apply).flatMap(fromRlp)

  def fromRlp(item: RlpItem): Either[TrieError, TrieNode] = item match
    case _: RlpItem.Bytes        => Left(TrieError.NotANodeStructure)
    case RlpItem.Sequence(items) =>
      if items.length == 2 then shortNodeFromRlp(items(0), items(1))
      else if items.length == BranchArity then branchFromRlp(items)
      else Left(TrieError.WrongNodeArity(items.length))

  /** A child slot holds the empty byte string, a 32-byte digest, or an embedded
    * node — which is a list, and so is never confusable with either.
    *
    * An embedded node whose own encoding reaches [[InlineLimit]] is rejected:
    * [[cap]] cannot produce one, so a parent carrying one commits to a shape no
    * conforming implementation would have written for the same contents.
    *
    * The width is measured before the child is turned into a node, which is what
    * bounds this recursion: a nesting level costs at least two bytes, so a child
    * under the limit nests only a few levels further, and an oversized one is
    * refused without the subtree below it ever being walked. Measuring the
    * decoded node instead would walk and re-encode that subtree at every level
    * on the way back up only to discard it.
    *
    * What it measures is the re-encoding of the item as received, and that equals
    * the received width only because [[org.fukuii.rlp.Rlp.decode]] refuses every
    * non-canonical spelling. **That dependency is real and lives in another
    * module**: relax any of those rejections and this rule silently starts
    * measuring bytes no peer sent. Nothing here enforces it and no test in this
    * module covers it.
    */
  def refFromRlp(item: RlpItem): Either[TrieError, NodeRef] = item match
    case RlpItem.Bytes(payload) =>
      if payload.isEmpty then Right(NodeRef.Empty)
      else
        Hash.fromBytes(payload) match
          case Right(hash) => Right(NodeRef.Hashed(hash))
          case Left(_)     => Left(TrieError.InvalidChildReference(payload.length))
    case embedded: RlpItem.Sequence =>
      val width = Rlp.encode(embedded).length
      if width >= InlineLimit then Left(TrieError.OversizedInlineNode(width))
      else fromRlp(embedded).map(NodeRef.Inline.apply)

  /** The child of a node that has exactly one, where the empty reference is not
    * a legitimate answer.
    *
    * [[refFromRlp]] decodes a child slot in general and admits the empty
    * reference, which is correct for one of a branch's sixteen and wrong here:
    * an extension exists to name a subtree, and [[cap]] produces the empty
    * reference only for an absent one. The two positions need different
    * decoders because a single one cannot see which caller it is answering —
    * which is exactly why the implementations that share one decoder between
    * both positions cannot refuse this shape.
    */
  def subnodeFromRlp(item: RlpItem): Either[TrieError, NodeRef] =
    refFromRlp(item).flatMap {
      case NodeRef.Empty => Left(TrieError.EmptyExtensionChild)
      case named         => Right(named)
    }

  private def shortNodeFromRlp(path: RlpItem, payload: RlpItem): Either[TrieError, TrieNode] = path match
    case _: RlpItem.Sequence    => Left(TrieError.NotANodeStructure)
    case RlpItem.Bytes(compact) =>
      Nibbles.fromCompact(compact).flatMap { (nibbles, isLeaf) =>
        if isLeaf then
          payload match
            case RlpItem.Bytes(value) => Right(LeafNode(nibbles, Bytes.fromIArray(value)))
            case _: RlpItem.Sequence  => Left(TrieError.NotANodeStructure)
        else if nibbles.isEmpty then Left(TrieError.EmptyExtensionSegment)
        else subnodeFromRlp(payload).map(ExtensionNode(nibbles, _))
      }

  private def branchFromRlp(items: Vector[RlpItem]): Either[TrieError, TrieNode] =
    items(ChildCount) match
      case _: RlpItem.Sequence  => Left(TrieError.NotANodeStructure)
      case RlpItem.Bytes(value) =>
        val children = items.take(ChildCount).foldLeft[Either[TrieError, Vector[NodeRef]]](Right(Vector.empty)) {
          (accumulated, next) => accumulated.flatMap(soFar => refFromRlp(next).map(soFar :+ _))
        }
        children.map(BranchNode(_, Bytes.fromIArray(value)))

  /** Builds the node tree for `entries`, whose keys have already had their first
    * `level` nibbles consumed by ancestors.
    *
    * This is the trie's definition rather than one way of maintaining it: the
    * structure is a function of the key set alone, which is what makes the
    * commitment independent of the order entries were written in.
    *
    * `entries` is a `Map` because key uniqueness is load-bearing — at most one
    * key may terminate at any level, and a duplicate would silently produce a
    * different shape. The recursion's cost is proportional to the total key
    * length rather than to the entry count, and no intermediate node is
    * retained, which is the property implementation A trades away and B keeps.
    */
  def patricialize(entries: Map[Nibbles, Bytes], level: Int): Option[TrieNode] =
    if entries.isEmpty then None
    else
      val (arbitraryKey, arbitraryValue) = entries.head
      if entries.size == 1 then Some(LeafNode(arbitraryKey.drop(level), arbitraryValue))
      else
        val substring = arbitraryKey.drop(level)
        var prefixLength = substring.length
        val keys = entries.keysIterator
        while keys.hasNext && prefixLength > 0 do
          prefixLength = prefixLength.min(substring.commonPrefixLength(keys.next().drop(level)))
        if prefixLength > 0 then
          Some(ExtensionNode(substring.take(prefixLength), cap(patricialize(entries, level + prefixLength))))
        else
          val (terminating, descending) = entries.partition((key, _) => key.length == level)
          val grouped = descending.groupBy((key, _) => key(level))
          val subnodes =
            Vector.tabulate(ChildCount)(index => cap(patricialize(grouped.getOrElse(index, Map.empty), level + 1)))
          Some(BranchNode(subnodes, terminating.values.headOption.getOrElse(Bytes.Empty)))
