package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, Hash}
// The two digests are aliased because the precompiles that answer with them
// carry the ecosystem's names for the ENTRIES -- `Sha256`, `Ripemd160` -- and a
// nested class would otherwise shadow the primitive its own body calls. The
// alias names what each object is rather than renaming it: a digest.
import org.fukuii.crypto.{
  AltBn128,
  Blake2b,
  Keccak256,
  Ripemd160 as Ripemd160Digest,
  Secp256k1,
  Sha256 as Sha256Digest,
  Signature
}

/** A contract the machine runs natively rather than by interpreting code.
  *
  * ==What it charges is asked before it runs, and that order is consensus==
  *
  * An invocation that cannot pay never reaches [[run]]: both the specification
  * and go-ethereum settle the charge first and treat a shortfall as an
  * exceptional halt. Answering first and pricing afterwards would spend gas the
  * caller did not have, which is a divergence rather than an inefficiency.
  *
  * ==Two ways to fail, and they are answered in different places==
  *
  * Running out of gas is settled by the charge above, before [[run]] is
  * reached. Input the native cannot make sense of is [[run]]'s own, and the two
  * are not the same failure: the first is a caller who could not pay for an
  * answer, the second is a caller who paid and asked something that has none.
  *
  * ==A refusal is NOT an empty answer, and three of the natives here would
  * read as though it were==
  *
  * The earliest natives answer with nothing rather than declining -- a
  * signature that recovers no address produces no bytes and a SUCCESSFUL
  * invocation. That is the specification's own rule for them and it is not a
  * general one: a native introduced later refuses outright, halting
  * exceptionally and keeping nothing. Collapsing the two would make a refused
  * curve point look like a signature that failed to recover, which is a
  * different chain outcome -- the first reverts the invocation and the second
  * does not.
  *
  * ==Open by construction, which is why this is a trait and not an enum==
  *
  * The set a chain runs grows and shrinks, so the members cannot be enumerated
  * once: later proposals add entries, and a network can add its own. An `enum`
  * would close exactly the thing [[PrecompileSet]] exists to keep open.
  */
trait Precompile:

  /** What `input` costs, settled before anything is computed from it. */
  def gasFor(input: Bytes): BigInt

  /** The answer, or why the input has none.
    *
    * A refusal consumes the whole invocation: every member of [[Halt]] is an
    * exceptional halt, so a caller sees a failed call and gets nothing back.
    */
  def run(input: Bytes): Either[Halt, Bytes]

/** The natives this machine can run, each priced by whoever builds the set.
  *
  * ==What is here is the implementation; WHICH of them a chain runs is not==
  *
  * What is below is what the machine knows how to compute. A chain
  * configuration decides which of them it places, at which addresses, at which
  * prices -- and that composition is not here, because it is a network's and
  * this module is the machine's. [[PrecompileSet.Empty]] is what such a
  * composition is built over.
  *
  * Each takes its own prices as constructor arguments rather than reading them
  * from anywhere, so that a repricing is an argument at the point the entry is
  * built rather than an edit here. That is how the field expresses one:
  * go-ethereum swaps the registry's entry for a differently-priced type, and
  * `ethereumclassic/core-geth` -- which serves several networks from one
  * binary, as this project intends to -- does the same by reassigning the map
  * key.
  *
  * ==Case classes, so that two sets built twice can be compared==
  *
  * A precompile is priced data plus a computation the address already implies,
  * so structural equality is the right equality for one: two entries agree when
  * they are the same native at the same prices. Written as anonymous classes
  * these compared by reference, which made [[PrecompileSet]] -- and through it
  * [[EvmRules]] -- answer *different* for two identical configurations built
  * separately. [[PrecompileSet.equals]] records what that costs and why the
  * residual is safe.
  *
  * Prices, addresses and failure behavior for the four a chain can place from
  * genesis are `ethereum/execution-specs` @ `ccaaaba58`,
  * `forks/frontier/vm/precompiled_contracts/` and `forks/frontier/vm/gas.py`,
  * read against `ethereum/go-ethereum` @ `6bb0588ad`, `core/vm/contracts.go`
  * and `params/protocol_params.go`. [[ModExp]] arrives with a later proposal
  * and is `ethereum/execution-specs` @ `20f7f6271a`,
  * `forks/byzantium/vm/precompiled_contracts/modexp.py`, read against
  * `ethereum/go-ethereum-pow` @ `v1.10.26`, `core/vm/contracts.go`. The three
  * that answer over a curve arrive with two further proposals and are
  * `ethereum/EIPs` @ `dbfa6bee83`, `EIPS/eip-196.md` and `EIPS/eip-197.md`,
  * read against the same specification's
  * `forks/byzantium/vm/precompiled_contracts/alt_bn128.py` and the same
  * client's `core/vm/contracts.go`.
  */
object Precompile:

  /** The two values this fork's `v` may take, and nothing else.
    *
    * They are 27 and 28 rather than 0 and 1 because the value carries an offset
    * the curve's own recovery identifier does not, and the curve has four
    * identifiers where this admits two. The whole 256-bit word is compared, so
    * a value agreeing in its low byte and carrying anything above it is a
    * different number and is refused.
    */
  private val LowRecovery: BigInt = BigInt(27)

  private val HighRecovery: BigInt = LowRecovery + 1

  /** How much of the input a signature occupies: a hash, a `v`, an `r` and an
    * `s`, each one word wide.
    */
  private val SignatureWidth: Int = 4 * Word.Width

  /** Recovers the address that signed a hash.
    *
    * Flat-priced, because the input it reads is a fixed width however much was
    * supplied.
    */
  final case class EcRecover(gas: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = gas
    def run(input: Bytes): Either[Halt, Bytes] = Right(signerOf(input))

  /** The SHA-256 digest of the input. */
  final case class Sha256(base: BigInt, perWord: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = costPerWord(base, perWord, input)
    def run(input: Bytes): Either[Halt, Bytes] = Right(Bytes.fromIArray(Sha256Digest.hash(input.toIArray).toBytes))

  /** The RIPEMD-160 digest, left-padded into a whole word.
    *
    * The digest is 20 bytes and the answer is 32, so the padding is part of the
    * contract rather than a convenience for the caller.
    */
  final case class Ripemd160(base: BigInt, perWord: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = costPerWord(base, perWord, input)
    def run(input: Bytes): Either[Halt, Bytes] = Right(leftPadded(Ripemd160Digest.hash(input.toIArray)))

  /** The input, unchanged. */
  final case class Identity(base: BigInt, perWord: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = costPerWord(base, perWord, input)
    def run(input: Bytes): Either[Halt, Bytes] = Right(input)

  /** How the difficulty of multiplying operands of a declared width is worked
    * out.
    *
    * ==A case rather than a function, for the reason [[GasForwarding]] is==
    *
    * A `BigInt => BigInt` member would read naturally here and is closed off: a
    * [[Precompile]] sits inside a [[PrecompileSet]], which sits inside
    * [[EvmRules]], whose value comparison answers *"do these two networks run
    * the same rules"* -- and equality on a function is unspecified, so one
    * member of that shape puts the whole comparison back to reference identity.
    * A case carries no quantity for a caller to get wrong either; both figures
    * either scheme spends stay [[GasSchedule]]'s.
    *
    * ==Two cases, and the second arrives with a divisor and a floor that move
    * with it==
    *
    * The scheme, the divisor and the floor are one document's three changes
    * rather than three independent settings, so a network moving one without the
    * others is expressible and is no network. Nothing here refuses it; what the
    * separation buys is that each is stated where its own kind belongs -- the two
    * figures in the schedule with every other price, the scheme here because it
    * is not a figure.
    */
  enum ModExpComplexity:

    /** Three branches over the declared width in bytes, quadratic in each.
      *
      * `ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-198.md` gives it as
      * `mult_complexity`, and the branches meet at 64 and 1024 bytes.
      */
    case Piecewise

    /** The square of the number of eight-byte words the declared width occupies,
      * rounded up.
      *
      * *"`max_length = max(base_length, modulus_length); words =
      * math.ceil(max_length / 8); return words**2`"* (`ethereum/EIPs` @
      * `dbfa6bee8`, `EIPS/eip-2565.md`, Final). `ethereum/execution-specs` @
      * `20f7f6271` reaches the same expression at
      * `forks/berlin/vm/precompiled_contracts/modexp.py:87-88`.
      *
      * **The word here is eight bytes and not the machine's thirty-two.** Both
      * appear in this file's arithmetic, and the difficulty term is the only
      * place the smaller one is meant.
      */
    case SquaredWordCount

    /** The difficulty of multiplying operands `length` bytes wide.
      *
      * Every division is a floor, both operands being non-negative.
      */
    def of(length: BigInt): BigInt = this match
      case Piecewise =>
        if length <= 64 then length * length
        else if length <= 1024 then length * length / 4 + 96 * length - 3072
        else length * length / 16 + 480 * length - 199680
      case SquaredWordCount =>
        val words = (length + 7) / 8
        words * words

  /** `base ** exponent mod modulus`, over operands whose widths the input
    * itself declares.
    *
    * ==Six fields, and only the first three are at fixed places==
    *
    * Three lengths, one word each, then the base, the exponent and the modulus
    * laid end to end at exactly those lengths. Every read is right-padded with
    * zeroes and everything past the last operand is ignored, so no input is
    * malformed: a caller supplying nothing has declared three lengths of zero.
    *
    * ==The charge is settled from the declared lengths, not from the
    * operands==
    *
    * The difficulty of multiplying numbers that wide, times the squarings an
    * exponent that long implies, over [[divisor]]. The only part of an operand
    * that reaches the price is the position of the highest set bit in the
    * exponent's first word, which is what lets the whole charge be settled
    * before an operand is read.
    *
    * ==Arbitrary precision throughout, and deliberately no ceiling==
    *
    * A declared length is a whole 256-bit word and the difficulty term squares
    * it, so an intermediate exceeds anything a machine integer holds. Nothing
    * here narrows or saturates, which makes the charge the specification's
    * exact figure.
    *
    * Two clients whose gas is a machine integer cannot do that, and they fail
    * differently. `ethereum/go-ethereum-pow` @ `v1.10.26` computes the whole
    * product in arbitrary precision and answers `math.MaxUint64` only once it
    * will not fit, which is a figure no transaction can supply after its
    * intrinsic charge -- so it refuses exactly where this refuses.
    * `besu-eth/besu` @ `fdf1247c6d` saturates INSIDE the formula, its
    * `square()` pinning the squared length at `Long.MAX_VALUE` before the rest
    * of the term is worked out, so what comes back can be far below its own
    * ceiling and payable: for a base declared 2**42 bytes wide it answers
    * 28928590731427686 against 60446291086284574991820 exact. So the two are
    * different numbers rather than one ceiling standing in for the other.
    * Unlike geth's ceiling, besu's figure is one a 64-bit gas limit could
    * state, so what bounds that divergence is the gas a block makes available
    * rather than the gas a transaction can express -- and the smaller of the
    * two is above 2**54, some six million times a limit that fits in 32 bits.
    *
    * ==A floor of nothing is a floor, and is what a network below EIP-2565
    * states==
    *
    * A short input is then charged what the formula gives, which is routinely
    * under two hundred and is zero for some inputs. Holding [[floor]] at zero
    * there rather than making it optional is the same shape
    * [[GasSchedule.transactionCreate]] is held at zero for: a bound nothing can
    * fall below is the absence of a bound, written as the value it is.
    *
    * ==Two answers are settled without an exponentiation==
    *
    * A base and a modulus both declared empty answer with nothing. A modulus
    * of zero answers in zeroes at the modulus's declared length, that being
    * the length every answer takes.
    *
    * **The first of the two changes no answer here, and is kept anyway.** The
    * specification returns there so that an exponent declared wider than any
    * buffer is never built, and that matters because a pair of empty lengths
    * makes the difficulty term zero and so prices the call at nothing however
    * long the exponent claims to be. This implementation never builds one
    * either way: a modulus declared empty reads as zero, and the zero-modulus
    * branch below answers before the exponent is touched. So nothing
    * observable rests on the return. It stays because it is the rule the
    * specification states, and because without it the answer would rest on
    * two other branches meeting rather than on that rule.
    */
  final case class ModExp(divisor: BigInt, floor: BigInt, complexity: ModExpComplexity) extends Precompile:

    def gasFor(input: Bytes): BigInt =
      val baseLength = lengthAt(input, 0)
      val exponentLength = lengthAt(input, Word.Width)
      val modulusLength = lengthAt(input, 2 * Word.Width)
      val exponentHead = valueAt(input, BaseOffset + baseLength, exponentLength.min(BigInt(Word.Width)))
      val worked =
        (complexity.of(baseLength.max(modulusLength)) *
          adjustedExponentLength(exponentLength, exponentHead).max(1)) / divisor
      worked.max(floor)

    def run(input: Bytes): Either[Halt, Bytes] = Right(answerFor(input))

    private def answerFor(input: Bytes): Bytes =
      val baseLength = lengthAt(input, 0)
      val exponentLength = lengthAt(input, Word.Width)
      val modulusLength = lengthAt(input, 2 * Word.Width)
      if baseLength == 0 && modulusLength == 0 then Bytes.Empty
      else
        val exponentOffset = BaseOffset + baseLength
        val modulusOffset = exponentOffset + exponentLength
        val answerWidth = asWidth(modulusLength)
        val modulus = valueAt(input, modulusOffset, modulusLength)
        if modulus == 0 then zeroes(answerWidth)
        else
          val base = valueAt(input, BaseOffset, baseLength)
          val exponent = valueAt(input, exponentOffset, exponentLength)
          leftPaddedTo(answerWidth, base.modPow(exponent, modulus))

  /** The sum of two points of the first group of `alt_bn128`.
    *
    * Flat-priced, because the input it reads is a fixed width however much was
    * supplied -- and it is supplied at any width, EIP-196 padding a short input
    * with zeroes and ignoring anything past the two points.
    */
  final case class AltBn128Add(gas: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = gas
    def run(input: Bytes): Either[Halt, Bytes] = answering(AltBn128.sum(input.toIArray))

  /** A point of the first group of `alt_bn128` scaled by a whole word.
    *
    * Flat-priced for [[AltBn128Add]]'s reason. The scalar is unconstrained --
    * one at or above the group's order names a point rather than an error --
    * so the only refusal here is the point itself.
    */
  final case class AltBn128Mul(gas: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = gas
    def run(input: Bytes): Either[Halt, Bytes] = answering(AltBn128.scaled(input.toIArray))

  /** Whether the pairing product over a list of point pairs is one.
    *
    * ==One "point" is a PAIR, which is what the price counts==
    *
    * EIP-197 gives the charge as `80 000 * k + 100 000` where *"`k` is the
    * number of points or, equivalently, the length of the input divided by
    * 192"* -- and 192 is a point from each group, so the document's "point" is
    * both of them together. Charging per encoded point would double the price.
    *
    * ==The charge is settled from a floor and the length rule is checked
    * after==
    *
    * An input whose length is not a whole number of pairs is charged for the
    * pairs it does contain and then refused, which is the specification's
    * order: it charges from `len(data) // 192` and raises afterwards. So a
    * caller supplying 191 bytes pays the base and gets nothing, and one who
    * cannot pay the base is turned away before the length is ever looked at.
    */
  final case class AltBn128PairingCheck(base: BigInt, perPoint: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = base + perPoint * BigInt(input.length / AltBn128.PairWidth)
    def run(input: Bytes): Either[Halt, Bytes] =
      AltBn128
        .pairingIsOne(input.toIArray)
        .map(held => Word(if held then BigInt(1) else BigInt(0)).toBytes)
        .toRight(Halt.InvalidParameter)

  /** BLAKE2b's compression function over EIP-152's packed argument.
    *
    * ==Priced per round, by a count the CALLER supplies==
    *
    * *"Each operation will cost `GFROUND * rounds` gas, where `GFROUND = 1`"*
    * (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-152.md`, Final). So this is the
    * one native here whose price is unbounded in its input's CONTENT rather
    * than its length: four bytes name up to 2^32-1 rounds, and a caller pays
    * for every one it asked for. `org.fukuii.crypto.Blake2b.rounds` reads the
    * count without ever narrowing it to an `Int`, which is where that would
    * otherwise go wrong.
    *
    * ==A malformed argument is charged nothing and then refused==
    *
    * The specification checks the width BEFORE charging and the final-block
    * byte AFTER (`ethereum/execution-specs` @ `20f7f6271a`,
    * `forks/istanbul/vm/precompiled_contracts/blake2f.py`), which this seam
    * cannot reproduce exactly: [[Precompile.gasFor]] settles a charge before
    * [[Precompile.run]] is reached and has no way to refuse.
    *
    * **BOTH refusals are priced at nothing here, and only one of them is
    * ordered as the specification orders it.** A width this native cannot read
    * is refused before the count could be believed, which is the specification's
    * own order. A final-block byte outside its two admitted values is refused
    * having been charged nothing, where the specification charges the rounds
    * first -- so this is the case where the two orders genuinely differ, and it
    * is worth naming rather than folding into the width.
    *
    * **They are indistinguishable to a caller even so, and that is a property
    * of what a refusal costs rather than an assumption.** Every [[Halt]] is an
    * exceptional halt, so a refused invocation keeps nothing whichever charge
    * preceded it -- charging zero and refusing, and charging the rounds and
    * refusing, both end with the caller having spent everything.
    * `Blake2fPrecompileSpec` asserts that equivalence by effect rather than
    * leaving it argued, which is what makes the divergence above safe to have
    * rather than merely explained.
    */
  final case class Blake2f(perRound: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt =
      Blake2b.rounds(input.toIArray).fold(BigInt(0))(count => perRound * BigInt(count))

    def run(input: Bytes): Either[Halt, Bytes] =
      Blake2b.compressPacked(input.toIArray).map(Bytes.fromIArray).toRight(Halt.InvalidParameter)

  /** An answer the curve produced, or the halt its absence means.
    *
    * The three natives above share one refusal because the curve gives them
    * one: a field element at or above the modulus, a point off the curve and a
    * point off the group are the same outcome to a caller, which is the whole
    * invocation failing and keeping nothing.
    */
  private def answering(answer: Option[IArray[Byte]]): Either[Halt, Bytes] =
    answer.map(Bytes.fromIArray).toRight(Halt.InvalidParameter)

  /** A settled charge plus one per whole word, counting a partial word as a
    * whole one.
    */
  private def costPerWord(base: BigInt, each: BigInt, input: Bytes): BigInt =
    base + each * BigInt((input.length + Word.Width - 1) / Word.Width)

  /** Where the operands begin, the three lengths ahead of them being one word
    * each.
    */
  private val BaseOffset: Int = 3 * Word.Width

  /** One of the three declared lengths, read as a whole word. */
  private def lengthAt(input: Bytes, offset: Int): BigInt =
    valueAt(input, BigInt(offset), BigInt(Word.Width))

  /** The number `width` bytes from `from` spell, reading past the end as
    * zeroes.
    *
    * ==The padding is computed rather than materialized, which is what makes
    * this total==
    *
    * `from` and `width` are both read out of 256-bit fields, so either can
    * name a window far larger than any buffer. Only the bytes actually present
    * are read; the zeroes behind them move the value left instead of being
    * written anywhere. So a window that starts past the end, or one whose
    * present bytes are all zero, answers zero at no cost however wide it claims
    * to be -- which is what a declared length large enough to matter produces,
    * since there are no bytes that far into any input.
    *
    * ==The one bound, and why it is refused rather than clamped==
    *
    * A window carrying a byte that is not zero, behind more padding than a
    * shift can count bits for, names a number this machine cannot represent.
    * Answering a clamped one would be answering a different number, so it is
    * refused instead. Nothing reaches it: the charge is settled before any
    * operand is read, and a length that wide prices the call above what a
    * 64-bit gas limit can supply -- except where the difficulty term is zero,
    * which takes a base and a modulus both declared empty, and which
    * [[ModExp.run]] answers without reading an operand at all.
    *
    * ==A precompile can decline its input now, and this deliberately does
    * not==
    *
    * Declining would refuse a call the specification computes an answer for,
    * which is a divergence rather than a safety net: nothing about a width this
    * large makes the input invalid, only unrepresentable by this machine. So it
    * stays a fault, and a run that reached it would be a defect in the charge
    * above rather than a fact to report to a caller.
    */
  private def valueAt(input: Bytes, from: BigInt, width: BigInt): BigInt =
    val available = (BigInt(input.length) - from).min(width).max(0)
    if available == 0 then BigInt(0)
    else
      val present = BigInt(1, bytesAt(input, from.toInt, available.toInt))
      val padding = (width - available) * 8
      if present == 0 then BigInt(0)
      else
        require(padding.isValidInt, "a right-padded read wider than the largest representable byte array")
        present << padding.toInt

  /** How many squarings an exponent of this length and leading word implies.
    *
    * Bytes past the first word count for eight each; within that word only the
    * position of the highest set bit counts, so the exponents this fork expects
    * to be common are charged for what they cost rather than for their width.
    * `ethereum/EIPs` @ `9e393a79`, `EIPS/eip-198.md` gives it as
    * `ADJUSTED_EXPONENT_LENGTH`, and the caller takes it against one, which is
    * where the document puts that floor.
    */
  private def adjustedExponentLength(length: BigInt, head: BigInt): BigInt =
    val highestBit = BigInt((head.bitLength - 1).max(0))
    if length < Word.Width then highestBit else 8 * (length - Word.Width) + highestBit

  /** A declared length as a width this machine can address.
    *
    * Every answer is as wide as the modulus was declared to be, so a length
    * past what an array can hold is one no answer exists for. It is refused
    * rather than clamped for [[valueAt]]'s reason, and is unreachable for the
    * same one.
    */
  private def asWidth(length: BigInt): Int =
    require(length.isValidInt, "a modulus wider than the largest representable byte array")
    length.toInt

  private def zeroes(width: Int): Bytes =
    Bytes.fromIArray(IArray.unsafeFromArray(new Array[Byte](width)))

  /** `value` in a buffer of `width` bytes, with its low-order end at the low-order
    * end of the buffer.
    *
    * The encoding a big integer carries is signed, so a value whose leading bit
    * is set gains a zero byte ahead of it that is not part of the number. Those
    * bytes are dropped by taking the low-order `width` of whatever arrives,
    * which is also what makes the answer the modulus's declared width whether
    * the value is narrower or the encoding is wider.
    */
  private def leftPaddedTo(width: Int, value: BigInt): Bytes =
    val out = new Array[Byte](width)
    val encoded = value.toByteArray
    val taken = if encoded.length < width then encoded.length else width
    var index = 0
    while index < taken do
      out(width - taken + index) = encoded(encoded.length - taken + index)
      index += 1
    Bytes.fromIArray(IArray.unsafeFromArray(out))

  private def bytesAt(input: Bytes, from: Int, width: Int): Array[Byte] =
    val raw = input.toIArray
    val out = new Array[Byte](width)
    var index = 0
    while index < width do
      out(index) = raw(from + index)
      index += 1
    out

  /** The address that signed, as a whole word, or nothing.
    *
    * ==Every rejection answers empty rather than halting==
    *
    * A `v` that is neither of the two admitted values, an `r` or `s` outside
    * the curve order, and a signature that recovers no point all produce the
    * same empty answer. The charge has already been made by the time this runs,
    * so a caller sees a successful invocation that returned nothing.
    *
    * ==The bounds on `r` and `s` are enforced by the recovery, not here==
    *
    * [[Secp256k1.recoverPublicKey]] answers nothing for either one outside
    * `[1, n-1]`, and its own documentation names that rule and its source.
    * Repeating the test here would give one rule two homes, which is how the
    * two come to disagree. What cannot be delegated is `v`: the recovery admits
    * the four identifiers the curve has, and this fork admits two of them.
    */
  private def signerOf(input: Bytes): Bytes =
    val padded = rightPadded(input, SignatureWidth)
    val messageHash = Hash.fromBytesTruncating(IArray.unsafeFromArray(window(padded, 0)))
    val v = wordAt(padded, Word.Width)
    val r = wordAt(padded, 2 * Word.Width)
    val s = wordAt(padded, 3 * Word.Width)
    if v != LowRecovery && v != HighRecovery then Bytes.Empty
    else
      Secp256k1
        .recoverPublicKey(messageHash, Signature(r, s, (v - LowRecovery).toInt))
        .map(addressWithin)
        .getOrElse(Bytes.Empty)

  /** The signer's address, as a whole word, from the recovered public key.
    *
    * The key arrives in the encoding the curve uses, whose first byte says
    * which form the rest is in; the digest is taken over the coordinates alone,
    * so that byte is dropped. An address is the digest's last twenty bytes, and
    * the answer is a word, so those bytes land where they already sit and
    * everything ahead of them stays zero.
    */
  private def addressWithin(publicKey: IArray[Byte]): Bytes =
    val digest = Keccak256.hash(coordinatesOf(publicKey)).toBytes
    val out = new Array[Byte](Word.Width)
    var index = Word.Width - Address.Width
    while index < Word.Width do
      out(index) = digest(index)
      index += 1
    Bytes.fromIArray(IArray.unsafeFromArray(out))

  private def coordinatesOf(publicKey: IArray[Byte]): IArray[Byte] =
    val out = new Array[Byte](publicKey.length - 1)
    var index = 0
    while index < out.length do
      out(index) = publicKey(index + 1)
      index += 1
    IArray.unsafeFromArray(out)

  /** `input` in a buffer of `width` bytes, zero-filled where it runs out.
    *
    * Reading past the end is not a fault: the specification pads rather than
    * refusing, so an input shorter than a signature is read as one whose
    * remaining fields are zero -- and those zeros are then rejected on their
    * own terms rather than by the input's length.
    */
  private def rightPadded(input: Bytes, width: Int): Array[Byte] =
    val out = new Array[Byte](width)
    val raw = input.toIArray
    var index = 0
    while index < width && index < raw.length do
      out(index) = raw(index)
      index += 1
    out

  /** A digest narrower than a word, in the low-order end of one. */
  private def leftPadded(digest: IArray[Byte]): Bytes =
    val out = new Array[Byte](Word.Width)
    var index = 0
    while index < digest.length do
      out(Word.Width - digest.length + index) = digest(index)
      index += 1
    Bytes.fromIArray(IArray.unsafeFromArray(out))

  private def window(padded: Array[Byte], from: Int): Array[Byte] =
    java.util.Arrays.copyOfRange(padded, from, from + Word.Width)

  private def wordAt(padded: Array[Byte], from: Int): BigInt =
    Word.fromBytes(Bytes.fromArray(window(padded, from))).toBigInt
