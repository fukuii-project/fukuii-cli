package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, Hex}
import org.fukuii.rlp.RlpCodec
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Logs against octets produced by the executable specification.
  *
  * ==Why this source, and it is the strongest one that exists==
  *
  * A block header could be certified against octets that shipped inside a real
  * block. A log cannot, and that was measured rather than assumed: probed
  * across all three fixture corpora on hand with `"bloom"` as a positive
  * control that fires in hundreds of files each, `"topics"` returns **zero**
  * everywhere, and the `"logs"` key that does appear holds a keccak of the logs
  * rather than the logs. Receipts are what publish log entries, and no corpus
  * here carries one.
  *
  * So the expectations come from running the specification — its own `Log` put
  * through its own RLP encoder — never from this project's reading of a
  * formula. The resource names the repository and the immutable tag.
  *
  * ==What the table is selected for==
  *
  * The shapes that change the octets: topic count from zero through five, both
  * sides of RLP's short/long-form boundary for the data field, a length past
  * 255 that needs two length bytes, and the fixed-width extremes.
  */
class LogPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(label: String, log: Log, rlp: String)

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    Vector(
      label = c(0),
      log = Log(
        address = Address.fromHex(c(1)).toOption.get,
        topics = if c(2) == "-" then Seq.empty else c(2).split(",").toSeq.map(Hash.fromHex(_).toOption.get),
        data = if c(3) == "-" then Bytes.Empty else Bytes.fromHex(c(3)).toOption.get
      ),
      rlp = c(4)
    )

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/log-vectors.txt"))
      .getOrElse(throw new IllegalStateException("log-vectors.txt is not on the test classpath"))
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val logs = Table(("vector"), vectors*)

  property("the table spans the topic counts and both sides of the length boundary") {
    val counts = vectors.map(_.log.topics.length).toSet
    val widths = vectors.map(_.log.data.length).toSet
    assert(
      Set(0, 1, 4, 5).subsetOf(counts) && Set(55, 56).subsetOf(widths) && widths.exists(_ > 255),
      s"topic counts ${counts.mkString(",")} and data widths ${widths.mkString(",")} must cover the shapes that change the octets"
    )
  }

  property("a log encodes to the octets the specification produced") {
    forAll(logs) { (v: Vector) =>
      assert(Hex.encode(RlpCodec.encodeTo(v.log)) == v.rlp, s"${v.label}: must match the specification")
    }
  }

  property("the specification's octets decode back to the same log") {
    forAll(logs) { (v: Vector) =>
      val bytes = Hex.decode(v.rlp).toOption.get
      assert(RlpCodec.decodeFrom[Log](bytes) == Right(v.log), s"${v.label}: round trip must be exact")
    }
  }

  /** A fifth topic is beyond anything `LOG4` can emit, and the encoding admits
    * it. A decoder that enforced the opcode's bound would reject a well-formed
    * entry, so the row exists to make that bound's absence a tested property
    * rather than an unstated one.
    */
  property("a log carrying more topics than any opcode emits still decodes") {
    val beyond = vectors.find(_.log.topics.length > Log.MaxTopicsFromOpcode).get
    assert(
      RlpCodec.decodeFrom[Log](Hex.decode(beyond.rlp).toOption.get) == Right(beyond.log),
      "no specification bounds the topic count, so neither may the decoder"
    )
  }

  /** Data of a single zero byte and data of no bytes are different values, and
    * their encodings differ in exactly one byte — `00` against `80`. An encoder
    * that treated the field as a scalar would minimize the first into the
    * second and drop a byte the contract emitted.
    */
  property("a single zero data byte does not collapse into the empty string") {
    val zeroByte = vectors.find(_.label == "data-single-zero-byte").get
    val empty    = vectors.find(_.label == "no-topics-no-data").get
    assert(
      zeroByte.rlp != empty.rlp && Hex.encode(RlpCodec.encodeTo(zeroByte.log)) == zeroByte.rlp,
      "0x00 is one byte of data, not an absent one"
    )
  }
