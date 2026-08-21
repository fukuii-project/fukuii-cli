package org.fukuii.chainspec

import org.fukuii.bytes.UInt64

/** When an upgrade takes effect, or the reason it does not.
  *
  * ==Two axes, kept apart, because one number cannot carry both==
  *
  * A proof-of-work network activates by block number and a proof-of-stake one
  * by timestamp, and the two are unrelated quantities that happen to share a
  * width. Collapsing them into one integer works for as long as every timestamp
  * on the network exceeds every block number on it, which is true of the
  * networks that were configured first and is not a property of anything:
  * `besu-eth/besu` at `c2addd9424` orders its whole schedule on a bare `long`
  * (`ScheduledProtocolSpec.Hardfork.compareTo`, driving
  * `DefaultProtocolSchedule`), so a development network configured with a fork
  * at block zero and another at timestamp zero has no defined order at all.
  * Here the axis is part of the value and comparison reads it first.
  *
  * ==Ordering across the axes is a specification rule, not a convention==
  *
  * EIP-6122 § Additional rules: *"Forks by timestamp MUST be scheduled at or
  * after the forks by block (on mainnet as well as on private networks)."* So
  * [[Activation.AtBlock]] precedes [[Activation.AtTimestamp]] whatever the two
  * numbers are, and that is the rule [[Schedule]] enforces rather than a tidy
  * default this module chose.
  *
  * ==Two ways of not activating, and they are NOT the same fact==
  *
  * This is a deliberate departure: no surveyed client separates them, and every
  * one of them wants to. `scroll-tech/go-ethereum` at `33c4686619`
  * (`params/config.go`) is the demonstration, in one file with no comment
  * needed. Its alpha network's configuration carries eight `nil` activations;
  * its mainnet configuration carries the same eight fields, and five of them
  * have moved to a real value while three have not. The five were *not yet
  * scheduled*. The three -- the DAO fork and two difficulty-bomb delays -- are
  * *never*, because a layer-two network has no difficulty bomb and took no DAO
  * fork. One token, two meanings, and the only thing separating them is
  * knowledge held outside the type.
  *
  * `ethereumclassic/core-geth` at `4185df450` has the same gap and writes the
  * missing half as prose: an activation field commented out with the reason its
  * network will never take that proposal. That configuration decomposes dozens
  * of activations and still had nowhere to put a permanent refusal.
  *
  * The trigger that would close this departure is a client adopting the
  * distinction in its own activation type.
  *
  * ==Neither case has an authored consumer, and that is stated rather than
  * hidden==
  *
  * No network authored in this build uses [[Unscheduled]] or [[Never]] -- both
  * are exercised only by this type's own tests and by a probe schedule. A
  * reader who notices that is right to ask, so the answer is here rather than
  * left to be inferred: both are cases the data will reach, and neither was
  * added to give the other one company.
  *
  * [[Unscheduled]] arrives with the first upgrade specified before it is dated,
  * which every network eventually has and `gnosischain/specs` at `045d46d6db`
  * already has. [[Never]] arrives with the first network that permanently
  * refuses a proposal the rest of the ecosystem took, and
  * `scroll-tech/go-ethereum` at `33c4686619` is already such a network -- its
  * mainnet configuration carries exactly three `nil` activations, every one of
  * them a permanent refusal, in the same field shape that holds a merely undated
  * activation elsewhere in the same file.
  *
  * **A refusal is not automatically a schedule entry**, and the distinction is
  * worth keeping straight: an upgrade a network declined and then never
  * mentions again belongs in whatever documents that network, because an entry
  * for it would resolve nothing, order nothing and reach nothing. What [[Never]]
  * is for is a configuration that must carry the field anyway -- one derived
  * per proposal from a shared template, where the absence of a value and a
  * decision not to take one are the same token unless something separates them.
  */
enum Activation:

  /** Active from this block number onward. */
  case AtBlock(number: UInt64)

  /** Active for every block whose timestamp is at or after this one. */
  case AtTimestamp(seconds: UInt64)

  /** Specified for this network and not yet given an activation point.
    *
    * `gnosischain/specs` at `045d46d6db` carries one: its Balancer upgrade's
    * own schedule table gives `-` for every network, and its text makes the
    * activation conditional on adoption rather than on a date.
    */
  case Unscheduled

  /** Specified elsewhere and permanently not this network's rule.
    *
    * Distinct from [[Unscheduled]] and the distinction is the whole point:
    * nothing later supplies a number, so a reader who confuses the two waits
    * for an activation that is not coming, or schedules one that must not.
    */
  case Never

  /** Which quantity this activation is measured in, where it is measured at
    * all.
    */
  def axis: Option[Activation.Axis] = this match
    case AtBlock(_)     => Some(Activation.Axis.Block)
    case AtTimestamp(_) => Some(Activation.Axis.Timestamp)
    case Unscheduled    => None
    case Never          => None

  /** The single unsigned 64-bit quantity this activation happens at, with the
    * axis dropped.
    *
    * ==The collapse is the specification's, and only here==
    *
    * EIP-2124 defines the fork identifier as a checksum over the activation
    * points that have passed, each *"regarded as `uint64` integers, encoded in
    * big endian format"*, and EIP-6122 extends the same treatment to timestamps
    * and states outright that *"it is not important to distinguish between a
    * timestamp or a block for `FORK_NEXT`."* `NethermindEth/nethermind` at
    * `c35ce1b1ab` feeds exactly this figure into the checksum
    * (`Nethermind.Network/ForkInfo.cs`, reading
    * `ForkActivation.Activation`).
    *
    * So the flat form is a real requirement with one consumer, and it is not
    * the representation: **ordering and lookup read [[axis]] first**, and a
    * caller reaching for this to compare two activations has thrown away the
    * thing that makes the comparison correct.
    *
    * One boundary worth knowing before this is used for that purpose: EIP-2124
    * states that a chain configured to start with a non-Frontier rule set in
    * its genesis *"is NOT considered a fork"*, so a schedule's genesis entry
    * contributes nothing to the checksum however its rules were composed.
    */
  def point: Option[UInt64] = this match
    case AtBlock(number)      => Some(number)
    case AtTimestamp(seconds) => Some(seconds)
    case Unscheduled          => None
    case Never                => None

object Activation:

  /** The quantity an activation is measured in.
    *
    * `NethermindEth/nethermind` at `c35ce1b1ab` keeps the same two-valued
    * distinction as `ForkActivationKind`, for the reason its own documentation
    * gives -- so that the choice does not ride on which numeric type a call
    * site happened to pass.
    */
  enum Axis:

    /** Block number. */
    case Block

    /** Seconds, as a block header states them. */
    case Timestamp

  private val byPoint: Ordering[UInt64] = summon[Ordering[UInt64]]

  /** Comparison that refuses rather than degrades.
    *
    * A total ordering would have to place [[Activation.Unscheduled]] and
    * [[Activation.Never]] somewhere, and every position is a claim that is not
    * true -- neither is before or after anything, because neither is anywhere.
    * `PartialOrdering` is the standard library's word for exactly that, and
    * `tryCompare` answering `None` is what a caller has to handle rather than
    * silently receive a number from.
    *
    * The cross-axis answer is not `None`. Block activations precede timestamp
    * activations by EIP-6122's own MUST, so the comparison is defined there and
    * ignores both numbers.
    */
  given activationOrdering: PartialOrdering[Activation] with

    def tryCompare(x: Activation, y: Activation): Option[Int] = (x, y) match
      case (AtBlock(a), AtBlock(b))         => Some(byPoint.compare(a, b))
      case (AtTimestamp(a), AtTimestamp(b)) => Some(byPoint.compare(a, b))
      case (AtBlock(_), AtTimestamp(_))     => Some(-1)
      case (AtTimestamp(_), AtBlock(_))     => Some(1)
      case (Unscheduled, Unscheduled)       => Some(0)
      case (Never, Never)                   => Some(0)
      case _                                => None

    def lteq(x: Activation, y: Activation): Boolean = tryCompare(x, y).exists(_ <= 0)
