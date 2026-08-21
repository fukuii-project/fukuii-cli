package org.fukuii.chainspec

import org.fukuii.bytes.UInt64
import org.fukuii.chainspec.networks.Ethereum

/** Values the specs in this package build schedules out of.
  *
  * The two networks are fictional and their chain ids are chosen not to be any
  * network's, because a schedule built here asserts nothing about what any real
  * network runs -- the invariants under test are properties of the shape, and a
  * recognizable chain id would invite them to be read as claims about that
  * chain.
  *
  * The two rule sets are whatever the machine already exposes. Which forks they
  * are is immaterial here; what matters is that they are two values that differ,
  * so a resolution answering with the wrong one is visible.
  */
object ChainspecFixtures:

  def number(value: Long): UInt64 = UInt64.fromLong(value).toOption.get

  val alpha: Network = Network(number(900001), "Alpha")

  val beta: Network = Network(number(900002), "Beta")

  def atBlock(value: Long): Activation = Activation.AtBlock(number(value))

  def atTimestamp(value: Long): Activation = Activation.AtTimestamp(number(value))

  val firstRules: ProtocolSpec = ProtocolSpec(Vector.empty, Ethereum.frontier.evm)

  val secondRules: ProtocolSpec = ProtocolSpec(Vector(ProposalId.Eip(7)), Ethereum.homestead.evm)

  val thirdRules: ProtocolSpec = ProtocolSpec(Vector(ProposalId.Eip(150)), Ethereum.tangerineWhistle.evm)

  def entry(
      activation: Activation,
      label: String,
      upgrade: Upgrade = Upgrade.ProtocolChange(firstRules),
      network: Network = alpha
  ): ScheduleEntry =
    ScheduleEntry(activation, UpgradeId.named(network, label), upgrade)

  /** The smallest schedule that satisfies every construction invariant. */
  val genesisOnly: Vector[ScheduleEntry] =
    Vector(entry(atBlock(0), "Start"))
