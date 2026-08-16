package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash, Hex}
import org.fukuii.rlp.RlpCodec
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Logs against octets that shipped in a block, and against the executable
  * specification for the shapes no block contains.
  *
  * ==Where a `corpus-` row comes from, and why it is the strongest tier==
  *
  * A receipt publishes three things in one object: its logs, the bloom taken
  * over them, and its own RLP — whose element 3 *is* the encoded log list. So a
  * log entry can be certified against bytes a client actually produced, rather
  * than against a specification run locally.
  *
  * Every such row was cross-verified before being written: element 3 sliced out
  * of the receipt's own octets, the fixture's separately-decoded JSON fields
  * re-encoded by an implementation independent of this one, and the two
  * required to agree. Over the whole corpus that check ran on 2193 receipts
  * carrying logs with zero mismatches.
  *
  * ==Why constructed rows remain, rather than being displaced==
  *
  * Three shapes no corpus row reaches. **Five topics** — the corpus carries
  * zero through four and no more, because no opcode emits a fifth, and the
  * absence of a bound is exactly what needs testing. **The 55/56-byte
  * boundary**, where RLP changes form. **The fixed-width extremes.** Each is
  * still encoded by the specification rather than by this project.
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

  property("the table spans every topic count and both sides of the length boundary") {
    val counts = vectors.map(_.log.topics.length).toSet
    val widths = vectors.map(_.log.data.length).toSet
    assert(
      (0 to 5).toSet.subsetOf(counts) && Set(55, 56).subsetOf(widths) && widths.exists(_ > 255),
      s"topic counts ${counts.mkString(",")} and data widths ${widths.mkString(",")} must cover the shapes that change the octets"
    )
  }

  /** Selecting corpus rows by sorting on `(topicCount, dataWidth)` and taking a
    * prefix fills the whole budget with 0- and 1-topic entries and drops 2
    * through 4 without saying so. That happened, and the property above is what
    * caught it. This one names the requirement directly so the next change to
    * the generator's selection cannot quietly re-narrow it.
    */
  property("the shipped-octet rows themselves span every topic count an opcode emits") {
    val fromCorpus = vectors.filter(_.label.startsWith("corpus-")).map(_.log.topics.length).toSet
    assert(
      (0 to Log.MaxTopicsFromOpcode).toSet.subsetOf(fromCorpus),
      s"corpus rows cover only ${fromCorpus.mkString(",")} topics — selection has narrowed"
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
