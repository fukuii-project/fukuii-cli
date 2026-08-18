package org.fukuii.trie

import org.fukuii.bytes.{Bytes, Hash}
import org.fukuii.storage.{
  InMemoryKeyValueStore,
  KeyValueStore,
  Layout,
  Namespace,
  NamespaceId,
  RepresentationId,
  Seam,
  Version,
  WriteMode
}

/** Construction shared by the trie specs, and the pairing that lets one
  * conformance suite run against both implementations of the seam.
  */
object TrieFixtures:

  /** A trie built for a named securing mode, over a store of its own. */
  type Build = Securing => Trie

  val layout: Layout = Layout(RepresentationId("phase-1-in-memory"), Set.empty)

  def store(): KeyValueStore = new InMemoryKeyValueStore(layout)

  def namespace(id: String): Namespace.Standalone =
    Namespace.Standalone(NamespaceId(id), Seam.State, WriteMode.Mutable)

  def version(label: Int): Version = Version(Hash.fromBytesTruncating(IArray(label.toByte)))

  def storedNode(securing: Securing): Trie =
    new StoredNodeTrie(securing, store(), namespace("state-nodes"))

  def derivedNode(securing: Securing): Trie =
    new DerivedNodeTrie(securing, store(), namespace("state-entries"), version(1))

  /** Both implementations of [[Trie]], named for the report a failing row
    * produces.
    */
  val implementations: Vector[(String, Build)] = Vector(
    "StoredNodeTrie" -> (storedNode(_)),
    "DerivedNodeTrie" -> (derivedNode(_))
  )

  def ascii(text: String): Bytes = Bytes.fromIArray(IArray.from(text.getBytes("US-ASCII")))

  def bytesOf(hex: String): Bytes = Bytes.fromHex(hex).toOption.get
