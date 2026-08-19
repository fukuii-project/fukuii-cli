package org.fukuii.storage

import org.fukuii.bytes.{Bytes, Hash}
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Using

/** Exercises [[InMemoryKeyValueStore]] against [[KeyValueStore]]'s contract:
  * atomicity of `update`, namespace and version isolation, the admission
  * invariant's readable effect, and the iterator's ordering and release —
  * including on a path that fails, which a write-then-read test alone cannot
  * reach.
  *
  * ==What is not tested here, and why==
  *
  * That `update`/`updateAt` refuse a [[Namespace.Coupled]] namespace is a
  * compile-time property of [[KeyValueStore]]'s signatures — they are typed
  * to accept only [[Namespace.Standalone]] — so there is no runtime call to
  * make and no passing test that could exercise it without itself failing to
  * compile. It is verified by this file compiling with `admit` as the only
  * operation that accepts a [[Namespace.Coupled]] value.
  *
  * ==What IS tested, and why it is a different guarantee==
  *
  * A *different* [[Namespace.Standalone]] value carrying the same
  * [[NamespaceId]] as an already-admitted [[Namespace.Coupled]] namespace
  * type-checks as an ordinary `update` argument — the signature above stops
  * the [[Namespace.Coupled]] value itself, never a same-id alias of it. That
  * half of the admission invariant is enforced at run time by
  * [[InMemoryKeyValueStore]]'s shape registry, and the "the admission
  * invariant" tests below exercise it directly, in both directions and
  * against a legitimate reuse that must not throw.
  */
class InMemoryKeyValueStoreSpec extends AnyFlatSpec:

  private def hexBytes(hex: String): Bytes = Bytes.fromHex(hex).toOption.get
  private def hexHash(hex: String): Hash = Hash.fromHex(hex).toOption.get

  private val layout = Layout(RepresentationId("hash-keyed"), Set(NamespaceId("chain-header")))
  private val stateNs: Namespace.Standalone = Namespace.Standalone(NamespaceId("state"), Seam.State, WriteMode.Mutable)
  private val otherNs: Namespace.Standalone = Namespace.Standalone(NamespaceId("code"), Seam.State, WriteMode.Mutable)
  private val version = Version(hexHash("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470"))
  private val otherVersion = Version(hexHash("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))

  private val keyA = hexBytes("0a")
  private val keyB = hexBytes("0b")
  private val valueA = hexBytes("aa")
  private val valueB = hexBytes("bb")

  "get" should "return None for a key that was never written" in {
    val store = new InMemoryKeyValueStore(layout)
    assert(store.get(stateNs, keyA).isEmpty, "an unwritten key must read as absent, not an error")
  }

  it should "return the value after update upserts it" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Nil, Seq(keyA -> valueA))
    assert(store.get(stateNs, keyA).contains(valueA), "the upserted value must be readable back")
  }

  "update" should "remove a key listed in removals" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Nil, Seq(keyA -> valueA))
    store.update(stateNs, Seq(keyA), Nil)
    assert(store.get(stateNs, keyA).isEmpty, "a removed key must read as absent")
  }

  it should "let the upsert win when a key appears in both removals and upserts" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Seq(keyA), Seq(keyA -> valueB))
    assert(store.get(stateNs, keyA).contains(valueB), "one atomic batch resolves an overlapping key to its upsert")
  }

  it should "not affect a different namespace holding the same key" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Nil, Seq(keyA -> valueA))
    assert(store.get(otherNs, keyA).isEmpty, "namespaces are independent keyspaces")
  }

  it should "not affect a different key in the same namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Nil, Seq(keyA -> valueA))
    assert(store.get(stateNs, keyB).isEmpty, "an update must not touch a key it was not given")
  }

  "updateAt" should "be isolated from a different version of the same namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    store.updateAt(stateNs, version, Nil, Seq(keyA -> valueA))
    assert(store.getAt(stateNs, otherVersion, keyA).isEmpty, "a write at one version must not appear at another")
  }

  it should "not be visible through the unversioned get" in {
    val store = new InMemoryKeyValueStore(layout)
    store.updateAt(stateNs, version, Nil, Seq(keyA -> valueA))
    assert(store.get(stateNs, keyA).isEmpty, "versioned and unversioned entries are separate keyspaces")
  }

  it should "make a write readable through getAt at the version it was written to" in {
    val store = new InMemoryKeyValueStore(layout)
    store.updateAt(stateNs, version, Nil, Seq(keyA -> valueA))
    assert(
      store.getAt(stateNs, version, keyA).contains(valueA),
      "a write must be readable back at the version it targeted"
    )
  }

  it should "reject a namespace coupled to itself rather than classifying the identifier" in {
    val store = new InMemoryKeyValueStore(layout)
    val itself: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable)
    val selfCoupled: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, itself)
    assertThrows[IllegalArgumentException](store.admit(selfCoupled, keyA, valueA, valueB))
  }

  it should "leave an identifier usable after refusing a self-coupled admit" in {
    val store = new InMemoryKeyValueStore(layout)
    val itself: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable)
    val selfCoupled: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, itself)
    val _ = intercept[IllegalArgumentException](store.admit(selfCoupled, keyA, valueA, valueB))
    store.update(itself, Nil, Seq(keyA -> valueA))
    assert(
      store.get(itself, keyA).contains(valueA),
      "a call that wrote nothing must not classify the identifier it refused"
    )
  }

  "admit" should "write the primary value, readable through the primary namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    val companion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val header: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    store.admit(header, keyA, valueA, valueB)
    assert(store.get(header, keyA).contains(valueA), "the primary value must be readable through the coupled namespace")
  }

  it should "write the companion value, readable through the companion namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    val companion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val header: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    store.admit(header, keyA, valueA, valueB)
    assert(
      store.get(companion, keyA).contains(valueB),
      "the companion value must be readable through its own namespace"
    )
  }

  "clear" should "remove every unversioned entry in the namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Nil, Seq(keyA -> valueA, keyB -> valueB))
    store.clear(stateNs)
    assert(store.get(stateNs, keyA).isEmpty, "clear must empty the whole unversioned namespace")
  }

  it should "remove every versioned entry in the namespace, across versions" in {
    val store = new InMemoryKeyValueStore(layout)
    store.updateAt(stateNs, version, Nil, Seq(keyA -> valueA))
    store.updateAt(stateNs, otherVersion, Nil, Seq(keyA -> valueA))
    store.clear(stateNs)
    assert(
      store.getAt(stateNs, version, keyA).isEmpty && store.getAt(stateNs, otherVersion, keyA).isEmpty,
      "clear must reach every version of the namespace"
    )
  }

  it should "not affect a different namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Nil, Seq(keyA -> valueA))
    store.update(otherNs, Nil, Seq(keyA -> valueA))
    store.clear(stateNs)
    assert(store.get(otherNs, keyA).contains(valueA), "clear must be scoped to the namespace it was given")
  }

  "close" should "cause a subsequent get to throw IllegalStateException" in {
    val store = new InMemoryKeyValueStore(layout)
    store.close()
    assertThrows[IllegalStateException](store.get(stateNs, keyA))
  }

  it should "cause a subsequent getAt to throw IllegalStateException" in {
    val store = new InMemoryKeyValueStore(layout)
    store.close()
    assertThrows[IllegalStateException](store.getAt(stateNs, version, keyA))
  }

  it should "cause a subsequent update to throw IllegalStateException" in {
    val store = new InMemoryKeyValueStore(layout)
    store.close()
    assertThrows[IllegalStateException](store.update(stateNs, Nil, Seq(keyA -> valueA)))
  }

  it should "cause a subsequent updateAt to throw IllegalStateException" in {
    val store = new InMemoryKeyValueStore(layout)
    store.close()
    assertThrows[IllegalStateException](store.updateAt(stateNs, version, Nil, Seq(keyA -> valueA)))
  }

  it should "cause a subsequent admit to throw IllegalStateException" in {
    val store = new InMemoryKeyValueStore(layout)
    val companion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val header: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    store.close()
    assertThrows[IllegalStateException](store.admit(header, keyA, valueA, valueB))
  }

  it should "cause a subsequent leaves to throw IllegalStateException" in {
    val store = new InMemoryKeyValueStore(layout)
    store.close()
    assertThrows[IllegalStateException](store.leaves(stateNs, version))
  }

  it should "cause a subsequent clear to throw IllegalStateException" in {
    val store = new InMemoryKeyValueStore(layout)
    store.close()
    assertThrows[IllegalStateException](store.clear(stateNs))
  }

  it should "be idempotent" in {
    val store = new InMemoryKeyValueStore(layout)
    store.close()
    store.close()
    succeed
  }

  "leaves" should "return entries in ascending byte order of the key" in {
    val store = new InMemoryKeyValueStore(layout)
    store.updateAt(stateNs, version, Nil, Seq(keyB -> valueB, keyA -> valueA))
    val ordered = Using.resource(store.leaves(stateNs, version))(_.toVector)
    assert(ordered == Vector(keyA -> valueA, keyB -> valueB), "leaves must sort ascending regardless of write order")
  }

  it should "return no entries for a version that was never written" in {
    val store = new InMemoryKeyValueStore(layout)
    val entries = Using.resource(store.leaves(stateNs, version))(_.toVector)
    assert(entries.isEmpty, "an unwritten version has no leaves")
  }

  it should "close the iterator even when the caller's body throws" in {
    val store = new InMemoryKeyValueStore(layout)
    store.updateAt(stateNs, version, Nil, Seq(keyA -> valueA))
    val it = store.leaves(stateNs, version)
    val threw =
      try
        Using.resource(it)(_ => throw new RuntimeException("boom"))
        false
      catch case _: RuntimeException => true
    assert(threw && it.isClosed, "the body's exception must propagate and close must still have run")
  }

  it should "make close idempotent" in {
    val store = new InMemoryKeyValueStore(layout)
    val it = store.leaves(stateNs, version)
    it.close()
    it.close()
    assert(it.isClosed, "closing twice must not throw and must leave the iterator closed")
  }

  "the admission invariant" should "reject a Standalone update whose namespace id aliases an already-Coupled namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    val companion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val header: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    store.admit(header, keyA, valueA, valueB)
    val aliasedHeader: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable)
    assertThrows[IllegalArgumentException](store.update(aliasedHeader, Nil, Seq(keyB -> valueA)))
  }

  it should "reject a Coupled admit whose namespace id aliases an already-Standalone namespace" in {
    val store = new InMemoryKeyValueStore(layout)
    store.update(stateNs, Nil, Seq(keyA -> valueA))
    val aliasedCompanion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val aliasedState: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("state"), Seam.State, WriteMode.Mutable, aliasedCompanion)
    assertThrows[IllegalArgumentException](store.admit(aliasedState, keyB, valueA, valueB))
  }

  it should "reject a Coupled admit that redeclares an already-registered id under a different companion" in {
    val store = new InMemoryKeyValueStore(layout)
    val companion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val header: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    store.admit(header, keyA, valueA, valueB)
    val otherCompanion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("other-companion"), Seam.ChainData, WriteMode.Mutable)
    val recoupledHeader: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, otherCompanion)
    assertThrows[IllegalArgumentException](store.admit(recoupledHeader, keyB, valueA, valueB))
  }

  it should "not record a namespace's own shape when its companion conflicts, leaving the primary id free to reuse" in {
    val store = new InMemoryKeyValueStore(layout)
    val unrelated: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("unrelated"), Seam.ChainData, WriteMode.Mutable)
    val decoyCoupled: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable, unrelated)
    store.admit(decoyCoupled, keyA, valueA, valueB)

    val conflictingCompanion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val header: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, conflictingCompanion)
    val _ = assertThrows[IllegalArgumentException](store.admit(header, keyB, valueA, valueB))

    val freshHeaderId: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable)
    store.update(freshHeaderId, Nil, Seq(keyB -> valueA))
    assert(store.get(freshHeaderId, keyB).contains(valueA), "a failed admit must not register its primary's shape")
  }

  it should "not reject a namespace value reused with the shape it was first seen with" in {
    val store = new InMemoryKeyValueStore(layout)
    val companion: Namespace.Standalone =
      Namespace.Standalone(NamespaceId("total-difficulty"), Seam.ChainData, WriteMode.Mutable)
    val header: Namespace.Coupled =
      Namespace.Coupled(NamespaceId("chain-header"), Seam.ChainData, WriteMode.Mutable, companion)
    store.admit(header, keyA, valueA, valueB)
    store.admit(header, keyB, valueB, valueA)
    assert(store.get(header, keyB).contains(valueB), "reusing the same Coupled shape for the same id must not throw")
  }
