package org.fukuii.evm

import org.fukuii.bytes.Address

/** The precompiles a chain runs, keyed by the address each answers at.
  *
  * ==A set is a value, and a fork or a network is a change to one==
  *
  * The same seam [[OpcodeTable]] sits on, reaching the other half of what a
  * chain configuration produces. Nothing here asks which fork is active,
  * because by the time a set exists that question has been answered.
  *
  * ==This is the part of the seam later forks exercise hardest==
  *
  * Every later fork adds an entry, and a network that runs this machine can add
  * its own or take one away. `ethereumclassic/core-geth` -- which serves
  * several networks from one binary, as this project intends to -- is the
  * worked case: `core/vm/contracts.go` starts from a `basePrecompiledContracts`
  * map holding exactly the four this project's chain configurations place, then
  * merges per-proposal additions over it, and expresses a repricing by
  * reassigning the key rather than by editing the base. That base map is a
  * NETWORK's, not the machine's, which is why the equivalent composition is not
  * in this module. `ethereum-optimism/op-geth` @ `86be6726f` carries its
  * chain-specific tokens under `core/vm` in one file, `contracts.go`, which is
  * the precompile registry -- so a network's divergence lands here rather than
  * in the machine.
  *
  * ==Changes come in three kinds, and only two need a combinator==
  *
  * Adding an entry, removing one, and repricing one. [[adding]] covers the
  * first and the third, because putting an entry at an address that already has
  * one replaces it. [[removing]] covers the second, and an address with no
  * entry is not a special state: the machine runs whatever code the account
  * holds, which for an address nothing has used is none.
  */
final class PrecompileSet private (private val entries: Map[Address, Precompile]):

  /** What `address` runs natively, or nothing where this chain runs code there.
    *
    * Nothing distinguishes an address that was never a precompile from one this
    * chain removed, which is the specification's own answer: neither is
    * special-cased, and an invocation of either runs the account's code.
    */
  def at(address: Address): Option[Precompile] = entries.get(address)

  def addresses: Set[Address] = entries.keySet

  def size: Int = entries.size

  /** The set with `precompile` at `address`, replacing whatever was there. */
  def adding(address: Address, precompile: Precompile): PrecompileSet =
    new PrecompileSet(entries.updated(address, precompile))

  /** The set without `address`, which then runs the account's code like any
    * other.
    */
  def removing(address: Address): PrecompileSet = new PrecompileSet(entries.removed(address))

  /** Whether two sets place the same natives at the same addresses at the same
    * prices.
    *
    * ==Why a class with no equality was not good enough==
    *
    * Two networks running the same rules is a question this project has to
    * answer -- [[EvmRules]] is compared as a whole to establish it -- and
    * every member of that record has to answer by value for the whole to. This
    * was one of the two that did not, so two identical configurations built
    * separately compared unequal while two references to one build compared
    * equal, which made the answer depend on how a test happened to be written
    * rather than on the rules.
    *
    * ==The residual, and why its direction is the safe one==
    *
    * [[Precompile]] is a trait and stays open, so a chain supplying its own
    * native as an anonymous class contributes reference equality to this
    * comparison. Such a set therefore compares unequal to an otherwise
    * identical one built separately.
    *
    * **That is a false negative and it cannot be a false positive.** A
    * differently-priced entry is a different value and never compares equal, so
    * this can refuse to confirm an agreement that exists but can never assert
    * one that does not. For a comparison that decides whether two chains run
    * the same rules, erring toward *different* is the direction to err in, and
    * the failure is a test going red rather than a claim going unchecked.
    */
  override def equals(other: Any): Boolean = other match
    case that: PrecompileSet => entries == that.entries
    case _                   => false

  override def hashCode: Int = entries.hashCode

object PrecompileSet:

  /** A chain that runs no precompile at all.
    *
    * Not a state any network is in, and it is what every chain configuration's
    * set is a change to -- so a set is always built up rather than cut down
    * from a hardcoded four.
    */
  val Empty: PrecompileSet = new PrecompileSet(Map.empty)

  /** Recovers the address that signed a hash. */
  val EcRecover: Address = addressOf(0x01)

  /** The SHA-256 digest of the input. */
  val Sha256: Address = addressOf(0x02)

  /** The RIPEMD-160 digest of the input, in the low end of a word. */
  val Ripemd160: Address = addressOf(0x03)

  /** The input, unchanged. */
  val Identity: Address = addressOf(0x04)

  /** `base ** exponent mod modulus`, at the first address above the four a
    * chain can place from its own genesis.
    */
  val ModExp: Address = addressOf(0x05)

  /** The sum of two points of the first group of `alt_bn128`. */
  val AltBn128Add: Address = addressOf(0x06)

  /** A point of the first group of `alt_bn128` scaled by a whole word. */
  val AltBn128Mul: Address = addressOf(0x07)

  /** Whether a list of point pairs of `alt_bn128` pairs to one. */
  val AltBn128PairingCheck: Address = addressOf(0x08)

  /** BLAKE2b's compression function, run for a number of rounds the caller
    * names.
    */
  val Blake2f: Address = addressOf(0x09)

  /** The address a low number names, which is how the field writes these: a
    * precompile sits at the low end of the space and everything above it is
    * zero.
    *
    * The whole number is read rather than its low byte, and the difference is
    * not academic -- a later proposal places one at an address two bytes wide.
    * A helper narrowing to a byte would answer the ZERO address for it, which
    * is a live account rather than an error, and a misplaced precompile is the
    * one mistake in this layer that compiles, runs, answers, and is found only
    * when two chains disagree.
    */
  private def addressOf(low: Int): Address =
    Address.fromBytesTruncating(
      IArray((low >>> 24).toByte, (low >>> 16).toByte, (low >>> 8).toByte, low.toByte)
    )
