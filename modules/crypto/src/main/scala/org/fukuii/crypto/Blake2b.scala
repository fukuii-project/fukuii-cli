package org.fukuii.crypto

/** The BLAKE2b compression function `F`, over the packed argument a precompile
  * hands it.
  *
  * ==Written out here because the provider cannot expose it==
  *
  * Every other primitive in this module delegates. This one cannot: the
  * provider ships BLAKE2b as a streaming digest, and `F` is the step underneath
  * it. At `bcgit/bc-java` @ `r1rv85`, `Blake2bDigest.compress` is `private` and
  * its round count is a hardcoded field, while the public surface is
  * `update`/`doFinal`/`reset` -- so the digest can be asked for a hash and
  * cannot be asked for one application of `F` at a caller's round count over a
  * caller's state vector. EIP-152 needs exactly that.
  *
  * ==The signature is the packed argument, because that is where both
  * endiannesses live==
  *
  * `F` takes six inputs and EIP-152 packs them into one string of bytes. The
  * decode is the whole risk here, and it is not uniform: `rounds` is BIG-endian
  * while `h`, `m` and the two offset counters are LITTLE-endian, and the result
  * goes back out little-endian. A layer that took decoded words would push that
  * split into its caller, where it is invisible -- so this takes the bytes and
  * the split is one file's problem.
  *
  * Three of the four implementations read for this draw the boundary the same
  * way, at `besu-eth/besu` @ `fdf1247c6d`, `NethermindEth/nethermind` @
  * `b92e2a4719` and `ethereum/execution-specs` @ `20f7f6271a`; the fourth,
  * `ethereum/go-ethereum` @ `e9e35a42f8`, decodes in the precompile and takes
  * words. Those four are what was read, and they are not the field.
  *
  * ==`rounds` is exposed because it is priced before it is spent==
  *
  * EIP-152 charges one gas per round, so a caller must read the round count
  * BEFORE compressing -- and all four implementations above read it themselves
  * for exactly that. Read big-endian out of four bytes it is a one-line
  * decode, and the one line is where the sign trap is, so it is written once
  * here rather than a second time in whatever prices it.
  *
  * ==The round count is unsigned, and never passes through a signed `Int`==
  *
  * EIP-152's `rounds` is a 32-bit UNSIGNED word, so it reaches 4294967295, and
  * the JVM has no unsigned 32-bit integer. Assembled through an `Int` the top
  * of that range reads as negative and the mixing loop runs zero times --
  * silently, returning a well-formed 64 bytes that no other client agrees
  * with. So the four bytes are widened one at a time into a `Long` and there is
  * no `Int` in the path at all: the value cannot be represented wrongly rather
  * than being corrected after the fact.
  *
  * ==The final-block flag is refused here, not left to the caller==
  *
  * EIP-152 admits `0` and `1` for `f` and makes every other byte an error. A
  * layer that read it as "nonzero is true" would compute an answer where a
  * conforming client halts, which is a chain split rather than a lenient
  * parse -- and the provider-shaped habit of accepting any nonzero byte is
  * exactly how that arrives. The two refusals EIP-152 names, a wrong length and
  * a wrong flag, are one observable to a caller -- both halt and consume the
  * call's gas -- so the refusal carries no reason with it.
  *
  * ==Where the constants came from==
  *
  * The initialization vector, the message schedule and the four rotation
  * amounts are RFC 7693's. None was transcribed: each was parsed from the
  * implementations named above and required to agree across them, the schedule
  * additionally by DERIVATION, since two of the four store it permuted into
  * their own call order rather than as the specification prints it.
  */
object Blake2b:

  /** How wide the state vector is, in bytes: eight 64-bit words.
    *
    * The compression function's output is the updated state vector, so this is
    * also the width of what comes back.
    */
  val StateWidth: Int = 64

  /** Where the state vector starts in the packed argument. */
  private val StateAt: Int = 4

  /** Where the message block starts: sixteen 64-bit words. */
  private val MessageAt: Int = StateAt + StateWidth

  /** Where the two offset counters start. */
  private val OffsetAt: Int = MessageAt + 128

  /** Where the final-block indicator sits: one byte, the last one. */
  private val FinalFlagAt: Int = OffsetAt + 16

  /** How wide EIP-152's packed argument is, in bytes.
    *
    * Derived from the layout above rather than stated, so the offsets and the
    * total cannot disagree. EIP-152 states the total independently and
    * `Blake2bSpec` asserts the two agree.
    */
  val PackedWidth: Int = FinalFlagAt + 1

  /** The only two bytes EIP-152 admits for the final-block indicator. */
  private val NonFinalBlock: Byte = 0
  private val FinalBlock: Byte = 1

  /** The four rotation amounts, RFC 7693 § 2.1's `(R1, R2, R3, R4)` for the
    * 64-bit flavor.
    */
  private val R1: Int = 32
  private val R2: Int = 24
  private val R3: Int = 16
  private val R4: Int = 63

  /** The initialization vector, RFC 7693 § 2.6.
    *
    * The same eight words SHA-512 starts from -- the fractional parts of the
    * square roots of the first eight primes.
    */
  private val Iv: IArray[Long] = IArray(
    0x6a09e667f3bcc908L, 0xbb67ae8584caa73bL, 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1L, 0x510e527fade682d1L,
    0x9b05688c2b3e6c1fL, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L
  )

  /** The message word schedule, RFC 7693 § 2.7's `SIGMA`.
    *
    * Ten permutations of the sixteen message words, one per round, consumed two
    * at a time by the eight mixes a round performs. A round past the tenth
    * wraps to the beginning, which is what makes a caller-supplied round count
    * meaningful at all.
    */
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

  /** The round count the packed argument asks for, or nothing where it is not
    * EIP-152's encoding.
    *
    * This is what a caller prices the call at, one gas per round.
    */
  def rounds(packed: IArray[Byte]): Option[Long] =
    if isWellFormed(packed) then Some(roundsOf(packed)) else None

  /** `F` over EIP-152's packed argument: the updated state vector, little-endian.
    *
    * Nothing where the argument is not that encoding -- the wrong length, or a
    * final-block indicator that is neither zero nor one.
    */
  def compressPacked(packed: IArray[Byte]): Option[IArray[Byte]] =
    if !isWellFormed(packed) then None
    else
      val updated = compress(
        roundsOf(packed),
        words(packed, StateAt, 8),
        words(packed, MessageAt, 16),
        littleEndianWord(packed, OffsetAt),
        littleEndianWord(packed, OffsetAt + 8),
        packed(FinalFlagAt) == FinalBlock
      )
      Some(littleEndianBytes(updated))

  /** `F` itself, RFC 7693 § 3.2, over words rather than over an encoding.
    *
    * Held back from the public surface because no caller needs it: a precompile
    * hands over bytes and takes bytes back. It is separate from
    * [[compressPacked]] rather than folded into it so that the encoding and the
    * mixing can be certified against different sources -- the encoding against
    * EIP-152, the mixing against the RFC's own vector and against the provider's
    * complete BLAKE2b, which drives this same function twelve rounds at a time.
    */
  private[crypto] def compress(
      rounds: Long,
      h: IArray[Long],
      m: IArray[Long],
      t0: Long,
      t1: Long,
      finalBlock: Boolean
  ): IArray[Long] =
    val v = new Array[Long](16)
    var i = 0
    while i < 8 do
      v(i) = h(i)
      v(i + 8) = Iv(i)
      i += 1
    v(12) ^= t0
    v(13) ^= t1
    if finalBlock then v(14) ^= -1L

    def mix(a: Int, b: Int, c: Int, d: Int, x: Long, y: Long): Unit =
      v(a) = v(a) + v(b) + x
      v(d) = java.lang.Long.rotateRight(v(d) ^ v(a), R1)
      v(c) = v(c) + v(d)
      v(b) = java.lang.Long.rotateRight(v(b) ^ v(c), R2)
      v(a) = v(a) + v(b) + y
      v(d) = java.lang.Long.rotateRight(v(d) ^ v(a), R3)
      v(c) = v(c) + v(d)
      v(b) = java.lang.Long.rotateRight(v(b) ^ v(c), R4)

    var round = 0L
    while round < rounds do
      val s = Schedule((round % Schedule.length).toInt)
      mix(0, 4, 8, 12, m(s(0)), m(s(1)))
      mix(1, 5, 9, 13, m(s(2)), m(s(3)))
      mix(2, 6, 10, 14, m(s(4)), m(s(5)))
      mix(3, 7, 11, 15, m(s(6)), m(s(7)))
      mix(0, 5, 10, 15, m(s(8)), m(s(9)))
      mix(1, 6, 11, 12, m(s(10)), m(s(11)))
      mix(2, 7, 8, 13, m(s(12)), m(s(13)))
      mix(3, 4, 9, 14, m(s(14)), m(s(15)))
      round += 1

    val out = new Array[Long](8)
    i = 0
    while i < 8 do
      out(i) = h(i) ^ v(i) ^ v(i + 8)
      i += 1
    IArray.unsafeFromArray(out)

  private def isWellFormed(packed: IArray[Byte]): Boolean =
    packed.length == PackedWidth &&
      (packed(FinalFlagAt) == NonFinalBlock || packed(FinalFlagAt) == FinalBlock)

  /** The round count, big-endian, widened without ever being an `Int`.
    *
    * Each byte is masked to a `Long` before it is shifted, so the top bit of
    * the leading byte lands at bit 31 of a 64-bit value rather than at the sign
    * bit of a 32-bit one.
    */
  private def roundsOf(packed: IArray[Byte]): Long =
    ((packed(0) & 0xffL) << 24) |
      ((packed(1) & 0xffL) << 16) |
      ((packed(2) & 0xffL) << 8) |
      (packed(3) & 0xffL)

  private def littleEndianWord(source: IArray[Byte], at: Int): Long =
    var word = 0L
    var i = 7
    while i >= 0 do
      word = (word << 8) | (source(at + i) & 0xffL)
      i -= 1
    word

  private def words(source: IArray[Byte], at: Int, count: Int): IArray[Long] =
    val out = new Array[Long](count)
    var i = 0
    while i < count do
      out(i) = littleEndianWord(source, at + i * 8)
      i += 1
    IArray.unsafeFromArray(out)

  private def littleEndianBytes(source: IArray[Long]): IArray[Byte] =
    val out = new Array[Byte](source.length * 8)
    var i = 0
    while i < source.length do
      var b = 0
      while b < 8 do
        out(i * 8 + b) = ((source(i) >>> (8 * b)) & 0xffL).toByte
        b += 1
      i += 1
    IArray.unsafeFromArray(out)
