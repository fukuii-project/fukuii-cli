package org.fukuii.evm

import org.fukuii.bytes.{Address, Bytes}
import org.scalatest.flatspec.AnyFlatSpec

/** The registry as a seam: what a placed set holds, and that a chain can change
  * it in each of the three ways the field changes one.
  *
  * The addresses are asserted here rather than assumed anywhere else, because
  * an address is the one value in this layer that is wrong silently — a
  * misplaced entry compiles, runs, and answers at a place no other client
  * answers. They are `ethereum/execution-specs` at `ccaaaba58`,
  * `frontier/vm/precompiled_contracts/__init__.py`, read against
  * `ethereum/go-ethereum` at `6bb0588ad`, `core/vm/contracts.go`, and besu's
  * `datatypes/.../Address.java` at `besu-eth/besu, c2addd9424`.
  */
class PrecompileSetSpec extends AnyFlatSpec:

  private val schedule = EvmFixtures.schedule
  private val placed = EvmFixtures.precompiles

  private def addressOf(low: Int): Address = Address.fromBytesTruncating(IArray(low.toByte))

  /** Answers something no placed entry answers, so a test can tell which of
    * two entries responded.
    */
  private val marker: Precompile = new Precompile:
    def gasFor(input: Bytes): BigInt = BigInt(1)
    def run(input: Bytes): Bytes = EvmFixtures.bytesOf("ff")

  // ── The addresses ────────────────────────────────────────────────────────

  "a set placed by a chain configuration" should "answer at the addresses it was given and no others" in
    assert(
      placed.addresses == Set(
        PrecompileSet.EcRecover,
        PrecompileSet.Sha256,
        PrecompileSet.Ripemd160,
        PrecompileSet.Identity
      ),
      "this fork defines four precompiles"
    )

  it should "place recovery at the first address" in
    assert(PrecompileSet.EcRecover == addressOf(0x01), "ecrecover answers at 0x01")

  it should "place the SHA-256 digest at the second" in
    assert(PrecompileSet.Sha256 == addressOf(0x02), "sha256 answers at 0x02")

  it should "place the RIPEMD-160 digest at the third" in
    assert(PrecompileSet.Ripemd160 == addressOf(0x03), "ripemd160 answers at 0x03")

  it should "place the copy at the fourth" in
    assert(PrecompileSet.Identity == addressOf(0x04), "identity answers at 0x04")

  it should "place modular exponentiation at the fifth" in
    // The address is the machine's and stays here whether or not a chain places
    // anything at it. `ethereum/EIPs` at `9e393a79`, EIPS/eip-198.md: "At
    // address 0x00......05, add a precompile".
    assert(PrecompileSet.ModExp == addressOf(0x05), "modexp answers at 0x05")

  it should "answer nothing at the address just above the last" in
    assert(
      placed.at(PrecompileSet.ModExp).isEmpty,
      "0x05 arrives at a later fork and is not this configuration's"
    )

  it should "answer nothing at the zero address" in
    assert(placed.at(addressOf(0x00)).isEmpty, "the zero address runs code like any other")

  it should "price recovery flat" in
    assert(
      placed.at(PrecompileSet.EcRecover).get.gasFor(Bytes.Empty) == schedule.precompileEcRecover,
      "the schedule is where the price comes from"
    )

  // ── The three kinds of change a chain makes ──────────────────────────────

  "a chain configuration" should "be able to add a precompile at a free address" in
    assert(
      placed.adding(addressOf(0x05), marker).at(addressOf(0x05)).isDefined,
      "every later fork adds one, so addition has to be expressible"
    )

  it should "leave the entries it already held in place when it adds one" in
    assert(
      placed.adding(addressOf(0x05), marker).size == placed.size + 1,
      "adding replaces nothing it did not name"
    )

  it should "be able to reprice one in place, by replacing the entry" in
    assert(
      placed.adding(PrecompileSet.Identity, marker).at(PrecompileSet.Identity).get.gasFor(Bytes.Empty) == BigInt(1),
      "a repricing is how the field expresses the commonest delta, and it is an addition at an occupied address"
    )

  it should "not grow when it reprices one" in
    assert(placed.adding(PrecompileSet.Identity, marker).size == placed.size, "replacing is not adding")

  it should "be able to remove one" in
    assert(
      placed.removing(PrecompileSet.Identity).at(PrecompileSet.Identity).isEmpty,
      "a network that runs this machine can take one away"
    )

  it should "leave the others in place when it removes one" in
    assert(placed.removing(PrecompileSet.Identity).size == placed.size - 1, "removal takes exactly what it names")

  it should "change nothing by removing an address that holds none" in
    assert(placed.removing(addressOf(0x05)).addresses == placed.addresses, "removal is not an error")

  "the empty set" should "answer nowhere" in
    assert(PrecompileSet.Empty.size == 0, "a chain's set is built up from this rather than cut down from a fixed four")
