package org.fukuii.consensus.pos

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.UInt64

/** The bound and the replacement rule, which are the two things an unbounded
  * map would get wrong while passing every other assertion.
  *
  * ==What a wrong implementation would still pass==
  *
  * A plain `Map[PayloadId, BuiltPayload]` answers every put-then-get assertion
  * correctly and leaks for the life of the node. A store that appended a second
  * entry for the same identifier instead of replacing would also answer
  * correctly, because the newest is scanned first — and would then evict a
  * different identifier's payload one insert sooner than it should.
  *
  * **Neither defect is visible from a retrieval.** The assertions that catch
  * them are the ones on [[PayloadStore.size]] and on what survives a run of
  * inserts, and they are the reason this suite exists at all.
  */
class PayloadStoreSpec extends AnyFlatSpec:

  private def idOf(byte: Int): PayloadId =
    PayloadId.fromBytes(IArray.fill(PayloadId.Width)(byte.toByte)).toOption.get

  private def payloadOf(number: Long): BuiltPayload =
    BuiltPayload(PosFixtures.bareCore.copy(blockNumber = UInt64.fromBits(number)))

  private val first = idOf(0x01)
  private val second = idOf(0x02)

  private def filled(capacity: Int, count: Int): PayloadStore =
    val store = PayloadStore(capacity)
    (1 to count).foreach(n => store.put(idOf(n), payloadOf(n.toLong)))
    store

  "PayloadStore.get" should "answer a payload that was stored" in {
    val store = PayloadStore()
    store.put(first, payloadOf(1L))
    assert(store.get(first).contains(payloadOf(1L)), "a build is collected by the identifier it was filed under")
  }

  it should "answer nothing for an identifier never stored" in
    assert(
      PayloadStore().get(first).isEmpty,
      "an identifier no build produced and one whose payload was evicted are alike to the protocol"
    )

  it should "keep two identifiers apart" in {
    val store = PayloadStore()
    store.put(first, payloadOf(1L))
    store.put(second, payloadOf(2L))
    assert(store.get(first).contains(payloadOf(1L)), "a second build must not answer for the first")
  }

  "PayloadStore.put, given an identifier it already holds" should "answer the newer payload" in {
    val store = PayloadStore()
    store.put(first, payloadOf(1L))
    store.put(first, payloadOf(99L))
    assert(store.get(first).contains(payloadOf(99L)), "a build improves its payload, and the newest is what is served")
  }

  it should "not grow" in {
    val store = PayloadStore()
    store.put(first, payloadOf(1L))
    store.put(first, payloadOf(99L))
    assert(
      store.size == 1,
      "appending instead of replacing answers correctly and evicts another identifier one insert sooner"
    )
  }

  "PayloadStore" should "hold no more than its capacity" in
    assert(
      filled(capacity = 3, count = 10).size == 3,
      "an identifier is minted every slot and nothing removes one, so an unbounded store leaks for the node's life"
    )

  it should "evict the oldest when full" in
    assert(
      filled(capacity = 3, count = 10).get(idOf(1)).isEmpty,
      "the first stored is the first dropped, which is what makes the bound a queue rather than a refusal"
    )

  it should "keep the newest when full" in
    assert(
      filled(capacity = 3, count = 10).get(idOf(10)).contains(payloadOf(10L)),
      "a consensus layer collects what it most recently asked for, so the newest is what must survive"
    )

  it should "keep exactly the newest run" in
    assert(
      Seq(8, 9, 10).forall(n => filled(capacity = 3, count = 10).holds(idOf(n))),
      "which entries survive is the eviction rule, and answering only the very newest would also pass a size check"
    )

  it should "drop everything older than the newest run" in
    assert(
      (1 to 7).forall(n => !filled(capacity = 3, count = 10).holds(idOf(n))),
      "a store that kept a stale entry outside its own bound would report a size that is not what it holds"
    )

  it should "hold a full capacity when given exactly that many" in
    assert(filled(capacity = 10, count = 10).size == 10, "the bound is a ceiling and not a smaller working set")

  "PayloadStore.holds" should "agree with get" in {
    val store = filled(capacity = 3, count = 10)
    assert(
      Seq(1, 5, 8, 9, 10).forall(n => store.holds(idOf(n)) == store.get(idOf(n)).isDefined),
      "a caller checking before collecting must not be told something different from what collecting answers"
    )
  }

  "PayloadStore" should "refuse a capacity of zero" in
    assert(
      scala.util.Try(PayloadStore(0)).isFailure,
      "a store that keeps nothing would refuse every collection while looking like it was working"
    )

  it should "default to the capacity the surveyed client uses" in
    assert(
      PayloadStore().capacity == PayloadStore.DefaultCapacity,
      "the figure is a tuning value rather than a protocol constant, which is why it is a parameter at all"
    )
