package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hash, Hex}
import org.fukuii.crypto.Keccak256
import org.fukuii.rlp.{Rlp, RlpItem}
import org.scalatest.flatspec.AnyFlatSpec

/** The node model, its encoding, the cap rule and the root rule.
  *
  * ==Two published constants are pinned here and neither is recalled==
  *
  * The empty-trie root is asserted against the value the executable
  * specification declares as `EMPTY_TRIE_ROOT` and go-ethereum declares as
  * `EmptyRootHash` — a specification and an implementation of it, agreeing. The
  * implementation under test derives it from the root rule instead of carrying
  * it, so a wrong root rule cannot be masked by a correct constant.
  */
class TrieNodeSpec extends AnyFlatSpec:

  private val publishedEmptyRoot = "56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"

  private def nibbles(values: Int*): Nibbles = Nibbles.fromValues(values).toOption.get

  private def bytesOf(hex: String): Bytes = Bytes.fromHex(hex).toOption.get

  private def repeated(byte: Int, count: Int): Bytes =
    Bytes.fromIArray(IArray.fill(count)(byte.toByte))

  "a leaf node" should "encode as its hex-prefixed path followed by its value" in {
    val encoded = TrieNode.toRlp(LeafNode(nibbles(1, 2, 3), bytesOf("ff")))
    assert(
      encoded == RlpItem.Sequence(
        Vector(RlpItem.Bytes(IArray(0x31.toByte, 0x23.toByte)), RlpItem.Bytes(IArray(0xff.toByte)))
      ),
      "a leaf is the two-item structure of its compact path and its value"
    )
  }

  "a branch node" should "encode as seventeen items" in {
    val encoded = TrieNode.toRlp(BranchNode(TrieNode.EmptyChildren, Bytes.Empty))
    assert(
      encoded == RlpItem.Sequence(Vector.fill(17)(RlpItem.Bytes(IArray.empty[Byte]))),
      "sixteen children then a value"
    )
  }

  it should "refuse construction with the wrong number of child slots" in
    assertThrows[IllegalArgumentException](BranchNode(Vector.empty, Bytes.Empty))

  "cap" should "name an absent subtree by the empty reference" in
    assert(TrieNode.cap(None) == NodeRef.Empty, "no node is named by the empty byte string")

  it should "embed a node whose encoding is shorter than the inline limit" in {
    val small = LeafNode(nibbles(1), bytesOf("ff"))
    assert(TrieNode.cap(Some(small)) == NodeRef.Inline(small), "a short node travels inside its parent")
  }

  it should "hash a node whose encoding reaches the inline limit" in {
    val large = LeafNode(nibbles(1), repeated(0xaa, 40))
    val encoded = TrieNode.encode(large)
    assert(TrieNode.cap(Some(large)) == NodeRef.Hashed(Keccak256.hash(encoded)), "a long node is referenced by digest")
  }

  private def isInline(ref: NodeRef): Boolean = ref match
    case _: NodeRef.Inline => true
    case _                 => false

  it should "switch from embedding to hashing exactly at the inline limit" in {
    val boundary = (1 to 40).map(size => LeafNode(nibbles(1), repeated(0xaa, size))).map { node =>
      (TrieNode.encode(node).length, TrieNode.cap(Some(node)))
    }
    assert(
      boundary.forall((width, ref) => (width < TrieNode.InlineLimit) == isInline(ref)),
      "the rule is on the encoded width and nothing else"
    )
  }

  "rootHash" should "be the published empty-trie root for an absent root node" in
    assert(
      TrieNode.rootHash(NodeRef.Empty).toHex == publishedEmptyRoot,
      "an empty trie commits to a published constant"
    )

  it should "hash the root node even where the inline rule would have embedded it" in {
    val small = LeafNode(nibbles(1), bytesOf("ff"))
    val expected = Keccak256.hash(TrieNode.encode(small))
    assert(TrieNode.rootHash(TrieNode.cap(Some(small))) == expected, "the root has no parent to be embedded into")
  }

  it should "return the digest unchanged for a root node the cap already hashed" in {
    val large = LeafNode(nibbles(1), repeated(0xaa, 40))
    val digest = Keccak256.hash(TrieNode.encode(large))
    assert(TrieNode.rootHash(TrieNode.cap(Some(large))) == digest, "a hashed root is not hashed a second time")
  }

  "fromRlp" should "round-trip a leaf" in {
    val node = LeafNode(nibbles(1, 2, 3), bytesOf("cafe"))
    assert(TrieNode.decode(TrieNode.encode(node)) == Right(node), "a leaf must survive its own encoding")
  }

  it should "round-trip an extension whose child is referenced by digest" in {
    val node = ExtensionNode(nibbles(4, 5), NodeRef.Hashed(Hash.fromBytesTruncating(IArray.fill(32)(0x11.toByte))))
    assert(TrieNode.decode(TrieNode.encode(node)) == Right(node), "an extension must survive its own encoding")
  }

  it should "round-trip a branch carrying an embedded child and a value" in {
    val embedded = NodeRef.Inline(LeafNode(nibbles(7), bytesOf("01")))
    val node = BranchNode(TrieNode.EmptyChildren.updated(3, embedded), bytesOf("02"))
    assert(TrieNode.decode(TrieNode.encode(node)) == Right(node), "a branch must survive its own encoding")
  }

  it should "reject a byte string where a node's list was required" in
    assert(TrieNode.fromRlp(RlpItem.Bytes(IArray(1.toByte))) == Left(TrieError.NotANodeStructure), "a node is a list")

  it should "reject a list that is neither two nor seventeen items" in
    assert(
      TrieNode.fromRlp(RlpItem.Sequence(Vector.fill(3)(RlpItem.Bytes(IArray.empty[Byte])))) ==
        Left(TrieError.WrongNodeArity(3)),
      "only a short node and a branch have node arities"
    )

  it should "reject a child reference that is neither empty nor a digest" in {
    val child = RlpItem.Bytes(IArray.fill(31)(0x11.toByte))
    val branch = RlpItem.Sequence(
      Vector.fill(16)(RlpItem.Bytes(IArray.empty[Byte])).updated(0, child) :+ RlpItem.Bytes(IArray.empty[Byte])
    )
    assert(TrieNode.fromRlp(branch) == Left(TrieError.InvalidChildReference(31)), "a 31-byte string names nothing")
  }

  it should "reject an embedded child whose encoding is exactly the inline limit" in {
    val exact = (1 to 40)
      .map(size => LeafNode(nibbles(1), repeated(0xaa, size)))
      .find(node => TrieNode.encode(node).length == TrieNode.InlineLimit)
      .getOrElse(fail("no leaf in the swept range encodes to exactly the inline limit"))
    val branch = RlpItem.Sequence(
      Vector.fill(16)(RlpItem.Bytes(IArray.empty[Byte])).updated(0, TrieNode.toRlp(exact)) :+
        RlpItem.Bytes(IArray.empty[Byte])
    )
    assert(
      TrieNode.fromRlp(branch) == Left(TrieError.OversizedInlineNode(TrieNode.InlineLimit)),
      "the rule embeds strictly under the limit, so a child at the limit is referenced by digest and never embedded"
    )
  }

  it should "reject an extension node that consumes no nibbles" in {
    val extension = RlpItem.Sequence(
      Vector(RlpItem.Bytes(IArray(0x00.toByte)), RlpItem.Bytes(IArray.fill(32)(0x11.toByte)))
    )
    assert(
      TrieNode.fromRlp(extension) == Left(TrieError.EmptyExtensionSegment),
      "an extension consumes a shared prefix, and descending one that consumes nothing makes no progress"
    )
  }

  it should "reject an embedded child whose own encoding reaches the inline limit" in {
    val oversized = TrieNode.toRlp(LeafNode(nibbles(1), repeated(0xaa, 40)))
    val branch = RlpItem.Sequence(
      Vector.fill(16)(RlpItem.Bytes(IArray.empty[Byte])).updated(0, oversized) :+ RlpItem.Bytes(IArray.empty[Byte])
    )
    assert(
      TrieNode.fromRlp(branch) == Left(
        TrieError.OversizedInlineNode(TrieNode.encode(LeafNode(nibbles(1), repeated(0xaa, 40))).length)
      ),
      "the cap rule cannot produce an embedded child that large"
    )
  }

  it should "reject bytes that are not RLP at all" in
    assert(TrieNode.decode(IArray(0xc1.toByte)).isLeft, "a truncated list is not a node")

  "patricialize" should "produce nothing for an empty entry set" in
    assert(TrieNode.patricialize(Map.empty, 0).isEmpty, "an empty trie has no root node")

  it should "produce a leaf holding the whole key for a single entry" in {
    val key = Nibbles.fromBytes(IArray(0xab.toByte))
    assert(
      TrieNode.patricialize(Map(key -> bytesOf("01")), 0) == Some(LeafNode(key, bytesOf("01"))),
      "one entry needs no branch"
    )
  }

  it should "produce a branch where two keys diverge at the first nibble" in {
    val entries = Map(
      Nibbles.fromBytes(IArray(0x10.toByte)) -> bytesOf("01"),
      Nibbles.fromBytes(IArray(0x20.toByte)) -> bytesOf("02")
    )
    assert(
      TrieNode.patricialize(entries, 0).exists {
        case _: BranchNode => true
        case _             => false
      },
      "divergence at nibble zero is a branch"
    )
  }

  it should "produce an extension where two keys share a leading nibble" in {
    val entries = Map(
      Nibbles.fromBytes(IArray(0x11.toByte)) -> bytesOf("01"),
      Nibbles.fromBytes(IArray(0x12.toByte)) -> bytesOf("02")
    )
    assert(
      TrieNode.patricialize(entries, 0).exists {
        case _: ExtensionNode => true
        case _                => false
      },
      "a shared prefix is an extension"
    )
  }

  it should "put a key that terminates at a branch into that branch's value slot" in {
    // Read at level 1, where the shorter key is exhausted: a key with nothing
    // left to consume is held by the branch rather than by a child.
    val entries = Map(nibbles(1) -> bytesOf("01"), nibbles(1, 2) -> bytesOf("02"))
    assert(
      TrieNode.patricialize(entries, 1).collect { case BranchNode(_, value) => value }.contains(bytesOf("01")),
      "a key ending exactly at a branch is held by the branch itself"
    )
  }

  it should "be independent of the order entries were collected in" in {
    val pairs = Vector(
      Nibbles.fromBytes(IArray(0x11.toByte)) -> bytesOf("01"),
      Nibbles.fromBytes(IArray(0x12.toByte)) -> bytesOf("02"),
      Nibbles.fromBytes(IArray(0x9f.toByte)) -> bytesOf("03")
    )
    val forwards = TrieNode.rootHash(TrieNode.cap(TrieNode.patricialize(pairs.toMap, 0)))
    val backwards = TrieNode.rootHash(TrieNode.cap(TrieNode.patricialize(pairs.reverse.toMap, 0)))
    assert(forwards == backwards, "the commitment is a function of the key set alone")
  }

  // Compared through Hex rather than with `==`: an IArray erases to an array,
  // whose equality is identity, so two equal encodings would compare unequal.
  "an empty reference" should "encode as the empty byte string" in
    assert(Hex.encode(Rlp.encode(TrieNode.refToRlp(NodeRef.Empty))) == "80", "absence is the empty string, not a zero")
