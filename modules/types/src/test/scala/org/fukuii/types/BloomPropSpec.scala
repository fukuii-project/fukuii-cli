package org.fukuii.types

import org.fukuii.bytes.Hex
import org.fukuii.rlp.RlpCodec
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** `M(O)` against blooms produced by the executable specification.
  *
  * ==Why an external source is not optional here==
  *
  * The construction sets bit `2047 - m` rather than bit `m`. Drop the
  * subtraction and the filter is a bit-reversed image of the real one: every
  * membership question it answers about its own output is still right, so it is
  * internally consistent and disagrees with the network on every block carrying
  * a log. A test written from this codebase's own reading of the formula would
  * agree with the same mistake.
  *
  * ==Two sources, and they were checked against each other==
  *
  * A `corpus-` row carries the bloom that shipped in the receipt beside the
  * very logs it was taken over. The rest come from running the specification's
  * `logs_bloom`. Those two were compared across the whole corpus — 2193
  * receipts carrying logs, zero disagreements — so neither source is resting on
  * the other's word.
  *
  * ==A row carries its logs as RLP, which couples this to the log decoder==
  *
  * That is deliberate rather than incidental. Reading a row exercises
  * [[Log]]'s decoder on the way to the bloom, so a fault there surfaces here
  * too — and [[LogPropSpec]] is what distinguishes the two, since it certifies
  * the decoder against its own octets independently.
  */
class BloomPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(label: String, logs: Seq[Log], bloom: Bloom)

  private def parse(line: String): Vector =
    val c = line.split(" ").toIndexedSeq
    Vector(
      label = c(0),
      logs =
        if c(1) == "-" then Seq.empty
        else c(1).split(",").toSeq.map(hex => RlpCodec.decodeFrom[Log](Hex.decode(hex).toOption.get).toOption.get),
      bloom = Bloom.fromHex(c(2)).toOption.get
    )

  private val vectors =
    val stream = Option(getClass.getResourceAsStream("/bloom-vectors.txt"))
      .getOrElse(throw new IllegalStateException("bloom-vectors.txt is not on the test classpath"))
    val source = scala.io.Source.fromInputStream(stream)
    try source.getLines().filterNot(l => l.isEmpty || l.startsWith("#")).map(parse).toVector
    finally source.close()

  private val blooms = Table(("vector"), vectors*)

  private def bitsSet(bloom: Bloom): Int =
    bloom.toBytes.foldLeft(0)((count, byte) => count + Integer.bitCount(byte & 0xff))

  private def entryCount(logs: Seq[Log]): Int = logs.map(_.topics.length + 1).sum

  property("the table spans no logs, several logs, and a repeated entry") {
    val labels = vectors.map(_.label).toSet
    assert(
      labels.contains("no-logs") && labels.contains("same-log-twice") && vectors.exists(_.logs.length > 1),
      s"${labels.mkString(", ")} must cover the empty case, a repeat, and a multi-log bloom"
    )
  }

  property("the bloom over a series of logs is the one the specification computed") {
    forAll(blooms) { (v: Vector) =>
      assert(Bloom.fromLogs(v.logs) == v.bloom, s"${v.label}: must match the specification")
    }
  }

  /** Three bits per indexable entry is the construction's own bound. Fewer is
    * legitimate — two of an entry's three groups can select one bit, and two
    * entries can collide — but more is not reachable, so exceeding it means
    * bits are being set that no entry asked for.
    */
  property("no bloom sets more bits than three per indexable entry") {
    forAll(blooms) { (v: Vector) =>
      assert(
        bitsSet(v.bloom) <= 3 * entryCount(v.logs),
        s"${v.label}: ${bitsSet(v.bloom)} bits from ${entryCount(v.logs)} entries exceeds three apiece"
      )
    }
  }

  property("no logs gives the empty bloom, which is 256 zero bytes and not an absent value") {
    val none = vectors.find(_.label == "no-logs").get
    assert(
      Bloom.fromLogs(Seq.empty) == Bloom.Empty && none.bloom == Bloom.Empty && bitsSet(Bloom.Empty) == 0,
      "the specification's empty bloom and this project's must be the same value"
    )
  }

  /** Bits are only ever set, so combining is `or` — idempotent. An accumulator
    * written with addition or exclusive-or agrees on every one of the other
    * rows and diverges on this one alone.
    */
  property("a repeated log sets no bit twice") {
    val once = vectors.find(_.label == "single-log-once").get
    val twice = vectors.find(_.label == "same-log-twice").get
    assert(
      Bloom.fromLogs(twice.logs) == once.bloom && twice.bloom == once.bloom,
      "or is idempotent, so the same entry contributes once however often it appears"
    )
  }

  /** The data field is not indexable. The pair differs only there, so an
    * implementation folding data into the filter gives two different blooms
    * while every single-log row still passes.
    */
  property("two logs differing only in data share one bloom") {
    val a = vectors.find(_.label == "data-does-not-contribute-a").get
    val b = vectors.find(_.label == "data-does-not-contribute-b").get
    assert(
      a.logs.head.data != b.logs.head.data && Bloom.fromLogs(a.logs) == Bloom.fromLogs(b.logs),
      "only the logger address and the topics are indexable"
    )
  }
