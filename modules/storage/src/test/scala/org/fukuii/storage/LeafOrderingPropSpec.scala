package org.fukuii.storage

import org.fukuii.bytes.{Bytes, Hash}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

import scala.util.Using

/** Table-driven coverage for [[InMemoryKeyValueStore.leaves]]'s ordering.
  *
  * [[InMemoryKeyValueStoreSpec]] covers the ordinary case; the rows here that
  * matter most are the length tiebreak — `0x01` sorts before `0x0100`
  * because their shared prefix agrees and the shorter key sorts first — and a
  * high first byte outranking a low one regardless of length, neither of
  * which a two-equal-length-key case can exercise.
  */
class LeafOrderingPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private def hexBytes(hex: String): Bytes = Bytes.fromHex(hex).toOption.get

  private val layout = Layout(RepresentationId("hash-keyed"), Set.empty)
  private val namespace: Namespace.Standalone =
    Namespace.Standalone(NamespaceId("state"), Seam.State, WriteMode.Mutable)
  private val version = Version(Hash.fromBytesTruncating(IArray.empty))

  private val cases = Table(
    ("name", "writtenInOrder", "expectedOrder"),
    ("already sorted", List("01", "02", "03"), List("01", "02", "03")),
    ("reverse sorted", List("03", "02", "01"), List("01", "02", "03")),
    ("length tiebreak on an agreeing prefix", List("0100", "01"), List("01", "0100")),
    ("a high first byte sorts after a low one of any length", List("ff", "0001"), List("0001", "ff"))
  )

  property("leaves returns entries in ascending byte order for every table row") {
    forAll(cases) { (name: String, writtenInOrder: List[String], expectedOrder: List[String]) =>
      val store = new InMemoryKeyValueStore(layout)
      val upserts = writtenInOrder.map(hex => hexBytes(hex) -> hexBytes(hex))
      store.updateAt(namespace, version, Nil, upserts)
      val observedKeys = Using.resource(store.leaves(namespace, version))(_.toVector.map(_._1.toHex))
      assert(observedKeys == expectedOrder, name + " must read back in ascending order")
    }
  }
