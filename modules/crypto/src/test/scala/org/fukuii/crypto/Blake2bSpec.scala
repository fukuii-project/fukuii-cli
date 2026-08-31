package org.fukuii.crypto

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.fukuii.bytes.Hex
import org.scalatest.Tag
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.propspec.AnyPropSpec

/** Certification of the BLAKE2b compression function against three oracles that
  * do not derive from each other.
  *
  * ==Why three, and not the vector table alone==
  *
  * A compression function returns sixty-four bytes for every input and has no
  * refusing state, so a wrong one is shaped exactly like a right one and cannot
  * be caught by exercising it. Only a published answer catches it. The three
  * used here are:
  *
  *  - **EIP-152 § Test Cases**, `ethereum/EIPs` @ `dbfa6bee83`, carried verbatim
  *    in `blake2f-vectors.txt`. Nine vectors: five with an output, four the
  *    document refuses. It reaches the packed encoding, both endiannesses, a
  *    zero round count, a non-final block and the top of the unsigned round
  *    range.
  *  - **RFC 7693 Appendix A**, the worked BLAKE2b-512 of the three bytes `abc`.
  *    Reachable from the compression function, and reached below by driving it
  *    as § 3.3's full hash rather than by asserting a state the RFC does not
  *    publish. Its digest is asserted equal to one EIP-152 states independently,
  *    so the literal below is checked by this suite rather than trusted.
  *  - **The provider's complete BLAKE2b**, `bcgit/bc-java` @ `r1rv85`. A
  *    different implementation by different authors, which cannot supply `F`
  *    and can supply the hash built on it -- so agreement over many message
  *    lengths exercises the mixing at many states rather than at one.
  *
  * ==The fourth arm is a control, and it is not evidence==
  *
  * [[derive]] below is a second derivation carrying the same constants as
  * parameters. **Its agreement with the module proves nothing**: it was written
  * from the same reading, so the two share whatever that reading got wrong. It
  * exists to make the negative arms possible -- the module's own constants are
  * private and cannot be perturbed from here -- and it earns that only because
  * the unperturbed instance is required to reproduce a PUBLISHED answer first.
  * A perturbation arm with no calibrated positive beside it would report a
  * failure it manufactured.
  */
class Blake2bSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private case class Vector(input: IArray[Byte], expected: Option[IArray[Byte]], name: String)

  /** EIP-152 § Test Cases, one per line: input, then either the output or
    * `REFUSED` where the document states an error, then the vector's number.
    */
  private val corpus: Seq[Vector] =
    val stream = Option(getClass.getResourceAsStream("/blake2f-vectors.txt"))
    val source = stream.map(scala.io.Source.fromInputStream(_))
    try
      source.toSeq.flatMap(_.getLines()).filter(_.nonEmpty).map { line =>
        val parts = line.split(' ')
        val input = if parts(0) == "-" then IArray.empty[Byte] else Hex.decode(parts(0)).toOption.get
        val expected = if parts(1) == "REFUSED" then None else Hex.decode(parts(1)).toOption
        Vector(input, expected, parts(2))
      }
    finally source.foreach(_.close())

  /** The round count, read independently of the module under test.
    *
    * A second decode rather than a convenience: the corpus is partitioned on
    * this, so partitioning on the module's own answer would let a broken decode
    * move the two-minute vector into the cheap table and hang an ordinary run.
    */
  private def roundsBigEndian(input: IArray[Byte]): Long =
    (0 until 4).foldLeft(0L)((acc, i) => (acc << 8) | (input(i) & 0xffL))

  private val computed = corpus.filter(_.expected.isDefined)
  private val refused = corpus.filter(_.expected.isEmpty)

  /** The one vector whose round count costs minutes rather than microseconds.
    *
    * At the measured 29 ns per round the top of the unsigned range is about two
    * minutes, which is what the project-wide heavy tag exists for.
    */
  private val Costly: Long = 1000000L
  private val cheap = computed.filter(v => roundsBigEndian(v.input) < Costly)
  private val costly = computed.filter(v => roundsBigEndian(v.input) >= Costly)

  // ─────────────────────────────── the second derivation, and its constants ──

  private val Iv: IArray[Long] = IArray(
    0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L, 0x510e527fade682d1L,
    0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
  )

  private val Schedule: IArray[IArray[Int]] = IArray(
    IArray(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
    IArray(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
    IArray(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
    IArray(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
    IArray(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
    IArray(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
    IArray(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
    IArray(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
    IArray(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
    IArray(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
  )

  private val Rotations: IArray[Int] = IArray(32, 24, 16, 63)

  private type Compressor = (Long, IArray[Long], IArray[Long], Long, Long, Boolean) => IArray[Long]

  /** RFC 7693 § 3.2, with the initialization vector, the message schedule and
    * the four rotation amounts supplied rather than fixed.
    */
  private def derive(iv: IArray[Long], schedule: IArray[IArray[Int]], rotations: IArray[Int]): Compressor =
    (rounds, h, m, t0, t1, finalBlock) =>
      val v = new Array[Long](16)
      var i = 0
      while i < 8 do
        v(i) = h(i)
        v(i + 8) = iv(i)
        i += 1
      v(12) ^= t0
      v(13) ^= t1
      if finalBlock then v(14) ^= -1L

      def mix(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long): Unit =
        v(a) = v(a) + v(b) + x
        v(d) = java.lang.Long.rotateRight(v(d) ^ v(a), rotations(0))
        v(c) = v(c) + v(d)
        v(b) = java.lang.Long.rotateRight(v(b) ^ v(c), rotations(1))
        v(a) = v(a) + v(b) + y
        v(d) = java.lang.Long.rotateRight(v(d) ^ v(a), rotations(2))
        v(c) = v(c) + v(d)
        v(b) = java.lang.Long.rotateRight(v(b) ^ v(c), rotations(3))

      var round = 0L
      while round < rounds do
        val s = schedule((round % schedule.length).toInt)
        mix(0, 4, 8, 12, m(s(0)), m(s(1)))
        mix(1, 5, 9, 13, m(s(2)), m(s(3)))
        mix(2, 6, 10, 14, m(s(4)), m(s(5)))
        mix(3, 7, 11, 15, m(s(6)), m(s(7)))
        mix(0, 5, 10, 15, m(s(8)), m(s(9)))
        mix(1, 6, 11, 12, m(s(10)), m(s(11)))
        mix(2, 7, 8, 13, m(s(12)), m(s(13)))
        mix(3, 4, 9, 14, m(s(14)), m(s(15)))
        round += 1

      IArray.tabulate(8)(j => h(j) ^ v(j) ^ v(j + 8))

  private def swapped[A: reflect.ClassTag](row: IArray[A], i: Int, j: Int): IArray[A] =
    IArray.tabulate(row.length)(k => if k == i then row(j) else if k == j then row(i) else row(k))

  private def replaced[A: reflect.ClassTag](rows: IArray[A], at: Int, value: A): IArray[A] =
    IArray.tabulate(rows.length)(k => if k == at then value else rows(k))

  // ──────────────────────────────────── RFC 7693 § 3.3, the full BLAKE2b-512 ──

  private val BlockWidth: Int = 128

  /** The parameter block an unkeyed 64-byte digest folds into the first state
    * word: `0x01` fanout, `0x01` depth, `0x40` digest length.
    */
  private val UnkeyedParameterBlock: Long = 0x01010040L

  private def leWord(source: IArray[Byte], at: Int): Long =
    (0 until 8).foldLeft(0L)((acc, i) => acc | ((source(at + i) & 0xffL) << (8 * i)))

  private def blockAt(message: IArray[Byte], at: Int): IArray[Long] =
    val padded = new Array[Byte](BlockWidth)
    var i = 0
    while i < BlockWidth && at + i < message.length do
      padded(i) = message(at + i)
      i += 1
    val block = IArray.unsafeFromArray(padded)
    IArray.tabulate(16)(w => leWord(block, w * 8))

  private def leBytes(words: IArray[Long]): IArray[Byte] =
    IArray.tabulate(words.length * 8)(i => ((words(i / 8) >>> (8 * (i % 8))) & 0xffL).toByte)

  /** The initial state vector of an unkeyed BLAKE2b-512. */
  private val InitialState: IArray[Long] =
    IArray.tabulate(8)(i => if i == 0 then Iv(0) ^ UnkeyedParameterBlock else Iv(i))

  private def blake2b512(message: IArray[Byte], compress: Compressor): IArray[Byte] =
    var state = InitialState
    var at = 0
    while message.length - at > BlockWidth do
      state = compress(12L, state, blockAt(message, at), (at + BlockWidth).toLong, 0L, false)
      at += BlockWidth
    leBytes(compress(12L, state, blockAt(message, at), message.length.toLong, 0L, true))

  private val Module: Compressor = Blake2b.compress
  private val Reference: Compressor = derive(Iv, Schedule, Rotations)

  /** RFC 7693 Appendix A, the unkeyed BLAKE2b-512 of the three bytes `abc`.
    *
    * Not trusted as written: a property below asserts it equals an output
    * EIP-152 states independently, so the two documents check each other and a
    * mistyped nibble here fails rather than weakening a check.
    */
  private val RfcAbcDigest: String =
    "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
      "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"

  private val Abc: IArray[Byte] = IArray('a'.toByte, 'b'.toByte, 'c'.toByte)

  /** A message whose every 64-bit word differs, spanning three blocks.
    *
    * The published vectors do not have this property -- see the two properties
    * at the foot of this file -- so a perturbation of the message schedule is
    * only visible against a message like this one.
    */
  private val Probe: IArray[Byte] = IArray.tabulate(300)(i => ((i * 37 + 11) % 256).toByte)

  private def bcDigest(message: IArray[Byte]): String =
    val digest = new Blake2bDigest(512)
    val buffer = Array.ofDim[Byte](message.length)
    var i = 0
    while i < message.length do
      buffer(i) = message(i)
      i += 1
    digest.update(buffer, 0, buffer.length)
    val out = new Array[Byte](digest.getDigestSize)
    val _ = digest.doFinal(out, 0)
    Hex.encode(IArray.unsafeFromArray(out))

  // ────────────────────────────────────────────────────────────── the corpus ──

  /** Guards against a vacuous pass.
    *
    * `forAll` over an empty table SUCCEEDS, so a resource that failed to load
    * would turn every table below into a check of nothing that still reports
    * green. EIP-152 states nine vectors, five with an output and four refused;
    * a corpus of any other shape is not the document.
    */
  property("the corpus loaded, and is EIP-152's nine vectors") {
    assert(
      corpus.length == 9 && computed.length == 5 && refused.length == 4,
      "expected 9 vectors, 5 computed and 4 refused; got " + corpus.length.toString +
        ", " + computed.length.toString + " and " + refused.length.toString
    )
  }

  property("exactly one vector sits at a round count that costs minutes") {
    assert(costly.length == 1 && cheap.length == 4, "the heavy partition is " + costly.length.toString + " of 5")
  }

  property("the packed width is the width of every input EIP-152 computes an output for") {
    assert(
      computed.forall(_.input.length == Blake2b.PackedWidth),
      "PackedWidth is " + Blake2b.PackedWidth.toString + "; the corpus disagrees"
    )
  }

  property("every vector EIP-152 states an output for reproduces it") {
    val table = Table("vector", cheap*)
    forAll(table) { (v: Vector) =>
      assert(
        Blake2b.compressPacked(v.input).map(Hex.encode) == v.expected.map(Hex.encode),
        v.name + " produced " + Blake2b.compressPacked(v.input).map(Hex.encode).toString
      )
    }
  }

  /** The top of the unsigned round range, EIP-152's vector 8.
    *
    * Tagged by the project-wide name rather than by the tag object, which lives
    * in a test tree this module has no edge to and could not be given one --
    * the module it sits in already depends on this one. The build excludes the
    * NAME, so the exclusion reaches this without a build change.
    *
    * It is the only arm that runs the mixing loop past the top of a signed
    * 32-bit count. The decode is asserted separately and cheaply below, so an
    * ordinary run still fails on the half of the trap it can afford to check.
    */
  property("the vector at the top of the unsigned round range reproduces it", Tag("org.fukuii.Heavy")) {
    val table = Table("vector", costly*)
    forAll(table) { (v: Vector) =>
      assert(
        Blake2b.compressPacked(v.input).map(Hex.encode) == v.expected.map(Hex.encode),
        v.name + " at " + roundsBigEndian(v.input).toString + " rounds did not reproduce"
      )
    }
  }

  property("every vector EIP-152 refuses is refused") {
    val table = Table("vector", refused*)
    forAll(table) { (v: Vector) =>
      assert(Blake2b.compressPacked(v.input).isEmpty, v.name + " was answered rather than refused")
    }
  }

  property("a refused argument is priced at nothing, because its round count is unreadable") {
    val table = Table("vector", refused*)
    forAll(table) { (v: Vector) =>
      assert(Blake2b.rounds(v.input).isEmpty, v.name + " reported a round count")
    }
  }

  /** The unsigned round count, checked without paying for it.
    *
    * EIP-152's `rounds` is a 32-bit UNSIGNED word. Assembled through an `Int`
    * the top of that range is negative and the mixing loop runs zero times, so
    * this asserts the decode over every vector -- including the one whose count
    * is 4294967295 -- against a decode written separately above.
    */
  property("the round count is read big-endian and without a sign") {
    val table = Table("vector", computed*)
    forAll(table) { (v: Vector) =>
      assert(
        Blake2b.rounds(v.input).contains(roundsBigEndian(v.input)),
        v.name + " reported " + Blake2b.rounds(v.input).toString
      )
    }
  }

  property("the top of the unsigned range is read as itself, not as a negative") {
    assert(
      costly.forall(v => Blake2b.rounds(v.input).contains(4294967295L)),
      "the corpus's largest round count did not decode to 2^32 - 1"
    )
  }

  // ─────────────────────────────────────────────── RFC 7693, and the provider ──

  /** Ties the two documents together, so neither literal stands alone.
    *
    * EIP-152's vector 5 IS the RFC's worked example: its state vector is an
    * unkeyed BLAKE2b-512's initial one, its message block is `abc` padded, its
    * offset counter is three and its final-block flag is set. So the digest the
    * RFC publishes and the output EIP-152 publishes are the same sixty-four
    * bytes, arrived at from different directions.
    */
  property("the RFC's published digest is the output EIP-152 states for the same call") {
    assert(
      computed.exists(v => v.expected.map(Hex.encode).contains(RfcAbcDigest)),
      "no EIP-152 vector produces RFC 7693 Appendix A's digest"
    )
  }

  property("EIP-152's vector for that call starts from an unkeyed BLAKE2b-512's state") {
    val stated = computed.find(v => v.expected.map(Hex.encode).contains(RfcAbcDigest)).get
    assert(
      Hex.encode(stated.input.slice(4, 68)) == Hex.encode(leBytes(InitialState)),
      "the state vector is " + Hex.encode(stated.input.slice(4, 68))
    )
  }

  property("driven as RFC 7693's full hash, the module reproduces its Appendix A digest") {
    assert(Hex.encode(blake2b512(Abc, Module)) == RfcAbcDigest, "got " + Hex.encode(blake2b512(Abc, Module)))
  }

  /** The provider's own BLAKE2b, over every message length that changes the
    * blocking: empty, part of a block, an exact block, an exact multiple, and
    * a remainder after several.
    */
  property("driven as the full hash, the module agrees with the provider at every blocking") {
    val lengths = Table("length", (0 to 300)*)
    forAll(lengths) { (n: Int) =>
      val message = IArray.tabulate(n)(i => ((i * 37 + 11) % 256).toByte)
      assert(Hex.encode(blake2b512(message, Module)) == bcDigest(message), "length " + n.toString)
    }
  }

  // ───────────────────────────────────────── the negative arms, and their control ──

  /** The positive control the arms below are worthless without.
    *
    * If the unperturbed second derivation did not reproduce a published answer,
    * every perturbation would fail for a reason that has nothing to do with the
    * perturbation.
    */
  property("the unperturbed second derivation reproduces the published digest") {
    assert(Hex.encode(blake2b512(Abc, Reference)) == RfcAbcDigest, "the control does not reproduce")
  }

  property("the unperturbed second derivation reproduces every EIP-152 output") {
    val table = Table("vector", cheap*)
    forAll(table) { (v: Vector) =>
      val h = IArray.tabulate(8)(i => leWord(v.input, 4 + i * 8))
      val m = IArray.tabulate(16)(i => leWord(v.input, 68 + i * 8))
      val out = Reference(roundsBigEndian(v.input), h, m, leWord(v.input, 196), leWord(v.input, 204), v.input(212) == 1)
      assert(v.expected.map(Hex.encode).contains(Hex.encode(leBytes(out))), v.name + " not reproduced by the control")
    }
  }

  private val perturbed: Seq[(String, Compressor)] = Seq(
    "one bit of the first IV word" -> derive(replaced(Iv, 0, Iv(0) ^ 1L), Schedule, Rotations),
    "one bit of the last IV word" -> derive(replaced(Iv, 7, Iv(7) ^ 1L), Schedule, Rotations),
    "two entries of schedule row 1 transposed" ->
      derive(Iv, replaced(Schedule, 1, swapped(Schedule(1), 0, 1)), Rotations),
    "two entries of schedule row 9 transposed" ->
      derive(Iv, replaced(Schedule, 9, swapped(Schedule(9), 14, 15)), Rotations),
    "the first rotation one short" -> derive(Iv, Schedule, replaced(Rotations, 0, 31)),
    "the second rotation one long" -> derive(Iv, Schedule, replaced(Rotations, 1, 25)),
    "the third rotation doubled" -> derive(Iv, Schedule, replaced(Rotations, 2, 32)),
    "the fourth rotation one short" -> derive(Iv, Schedule, replaced(Rotations, 3, 62))
  )

  /** The arms that must FAIL, and the whole reason the control above exists.
    *
    * Each perturbs one constant the compression function is built from and
    * requires the answer to stop reproducing. A published answer that survives a
    * changed constant is not certifying that constant.
    *
    * They are run against the provider rather than against the published
    * vectors, for the measured reason the two properties below pin: the
    * published vectors cannot see most of these.
    */
  property("every perturbation of a constant disagrees with the provider") {
    val table = Table("perturbation", perturbed*)
    forAll(table) { (arm: (String, Compressor)) =>
      assert(Hex.encode(blake2b512(Probe, arm._2)) != bcDigest(Probe), arm._1 + " still agreed")
    }
  }

  /** What the published corpus cannot decide, asserted rather than described.
    *
    * Every vector EIP-152 states carries the SAME message block -- the three
    * bytes `abc`, padded -- so fifteen of its sixteen words are zero, and RFC
    * 7693 Appendix A is that same call. Exchanging two schedule entries that
    * both index a zero word therefore changes nothing any of them computes: the
    * mixing consumes two equal values in place of two others.
    *
    * So the published corpus for this precompile certifies the schedule only
    * where a position indexes word zero. That is what makes the provider
    * cross-check load-bearing rather than a second opinion, and it is asserted
    * here so it fails if it ever stops being true.
    */
  property("every vector EIP-152 states leaves fifteen of sixteen message words zero") {
    val table = Table("vector", computed*)
    forAll(table) { (v: Vector) =>
      assert(
        (1 to 15).forall(i => leWord(v.input, 68 + i * 8) == 0L),
        v.name + " carries a message block the corpus was not thought to have"
      )
    }
  }

  property("a schedule transposition between two of those zero words survives every published vector") {
    val blind = derive(Iv, replaced(Schedule, 1, swapped(Schedule(1), 0, 1)), Rotations)
    assert(
      Hex.encode(blake2b512(Abc, blind)) == RfcAbcDigest,
      "the published corpus discriminates more of the schedule than it was measured to"
    )
  }
