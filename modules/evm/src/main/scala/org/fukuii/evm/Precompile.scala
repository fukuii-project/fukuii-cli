package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes, Hash}
// The two digests are aliased because the precompiles that answer with them
// carry the ecosystem's names for the ENTRIES -- `Sha256`, `Ripemd160` -- and a
// nested class would otherwise shadow the primitive its own body calls. The
// alias names what each object is rather than renaming it: a digest.
import org.fukuii.crypto.{Keccak256, Ripemd160 as Ripemd160Digest, Secp256k1, Sha256 as Sha256Digest, Signature}

/** A contract the machine runs natively rather than by interpreting code.
  *
  * ==What it charges is asked before it runs, and that order is consensus==
  *
  * An invocation that cannot pay never reaches [[run]]: both the specification
  * and go-ethereum settle the charge first and treat a shortfall as an
  * exceptional halt. Answering first and pricing afterwards would spend gas the
  * caller did not have, which is a divergence rather than an inefficiency.
  *
  * ==Failing is an answer here, not a refusal==
  *
  * [[run]] returns bytes for every input, and there is no shape in this
  * signature for declining. A precompile handed input it cannot make sense of
  * answers with nothing, having consumed its whole charge, and the invocation
  * SUCCEEDS -- the specification returns early leaving the output empty, and
  * go-ethereum returns a nil output beside a nil error. The one failure a
  * precompile at this fork can produce is running out of gas, and that is
  * settled by the charge above rather than by anything below.
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

  /** The answer, which is empty where the input cannot be made sense of. */
  def run(input: Bytes): Bytes

/** The natives this machine can run, each priced by whoever builds the set.
  *
  * ==What is here is the implementation; WHICH of them a chain runs is not==
  *
  * These four are what the machine knows how to compute. A chain configuration
  * decides which of them it places, at which addresses, at which prices -- and
  * that composition is not here, because it is a network's and this module is
  * the machine's. [[PrecompileSet.Empty]] is what such a composition is built
  * over.
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
  * [[ChainRules]] -- answer *different* for two identical configurations built
  * separately. [[PrecompileSet.equals]] records what that costs and why the
  * residual is safe.
  *
  * Prices, addresses and failure behavior are `ethereum/execution-specs` @
  * `ccaaaba58`, `forks/frontier/vm/precompiled_contracts/` and
  * `forks/frontier/vm/gas.py`, read against `ethereum/go-ethereum` @
  * `6bb0588ad`, `core/vm/contracts.go` and `params/protocol_params.go`.
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
    def run(input: Bytes): Bytes = signerOf(input)

  /** The SHA-256 digest of the input. */
  final case class Sha256(base: BigInt, perWord: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = costPerWord(base, perWord, input)
    def run(input: Bytes): Bytes = Bytes.fromIArray(Sha256Digest.hash(input.toIArray).toBytes)

  /** The RIPEMD-160 digest, left-padded into a whole word.
    *
    * The digest is 20 bytes and the answer is 32, so the padding is part of the
    * contract rather than a convenience for the caller.
    */
  final case class Ripemd160(base: BigInt, perWord: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = costPerWord(base, perWord, input)
    def run(input: Bytes): Bytes = leftPadded(Ripemd160Digest.hash(input.toIArray))

  /** The input, unchanged. */
  final case class Identity(base: BigInt, perWord: BigInt) extends Precompile:
    def gasFor(input: Bytes): BigInt = costPerWord(base, perWord, input)
    def run(input: Bytes): Bytes = input

  /** A settled charge plus one per whole word, counting a partial word as a
    * whole one.
    */
  private def costPerWord(base: BigInt, each: BigInt, input: Bytes): BigInt =
    base + each * BigInt((input.length + Word.Width - 1) / Word.Width)

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
