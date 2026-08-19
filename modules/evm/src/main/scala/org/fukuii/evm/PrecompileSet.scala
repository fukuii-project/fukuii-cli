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
  * map holding exactly the entries [[baseline]] holds, then merges per-proposal
  * additions over it, and expresses a repricing by reassigning the key rather
  * than by editing the base. `ethereum-optimism/op-geth` carries exactly one
  * chain-specific file under `core/vm`, and that file is its precompile
  * registry -- so a network's divergence lands here rather than in the machine.
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

object PrecompileSet:

  /** A chain that runs no precompile at all.
    *
    * Not a state any network is in, and it is what [[baseline]] is a change to
    * -- so a set is always built up rather than cut down from a hardcoded four.
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

  /** The precompiles the machine started with, priced by `schedule`.
    *
    * Named for what it is rather than for the fork that shipped it, following
    * [[OpcodeTable.baseline]] and the client both follow: a baseline shared by
    * every network this machine serves cannot carry one network's name for it.
    */
  def baseline(schedule: GasSchedule): PrecompileSet =
    Empty
      .adding(EcRecover, Precompile.ecRecover(schedule.precompileEcRecover))
      .adding(Sha256, Precompile.sha256(schedule.precompileSha256Base, schedule.precompileSha256PerWord))
      .adding(
        Ripemd160,
        Precompile.ripemd160(schedule.precompileRipemd160Base, schedule.precompileRipemd160PerWord)
      )
      .adding(Identity, Precompile.identity(schedule.precompileIdentityBase, schedule.precompileIdentityPerWord))

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
