package org.fukuii.chainspec

import org.fukuii.bytes.UInt64

/** What a scheduled upgrade does when it activates.
  *
  * ==Three answers, because the field needs all three==
  *
  * An upgrade states rules, or mutates state without touching the rules, or
  * does neither and is on the schedule because the network's own canonical
  * enumeration has it there. The second and third look alike from the rules'
  * point of view and are told apart by [[Schedule.forkPoints]], which is the
  * one place the difference is observable.
  *
  * ==Not every upgrade is a rule change, and two networks demand the second
  * case==
  *
  * EIP-779 says it of the first one outright: *"Unlike other hard forks, the
  * DAO Fork did not change the protocol; all EVM opcodes, transaction format,
  * block structure, and so on remained the same. Rather, the DAO Fork was an
  * 'irregular state change'"*. `gnosischain/specs` at `045d46d6db` reaches the
  * same shape independently -- its Balancer upgrade *"introduces an irregular
  * state change intended to recover funds"* and carries no rule change at all.
  *
  * A schedule that could only hold rule sets would have to leave those out, and
  * an upgrade missing from a schedule is missing from everything derived from
  * it. So the case is admitted here. **What such an upgrade does is not
  * modelled**: it needs a layer that can mutate state, which does not exist,
  * and a nullary case is what forces whoever builds that layer to add the
  * payload deliberately rather than find a plausible field already waiting.
  */
enum Upgrade:

  /** The rules the network runs from this activation onward. */
  case ProtocolChange(spec: ProtocolSpec)

  /** A one-time change to state that leaves the rules exactly as they were. */
  case IrregularStateChange

  /** Scheduled and named by the network, changing neither the rules nor the
    * state.
    *
    * ==Why a third case, when the second one already gates no rule==
    *
    * Because the two are not the same fact, and the difference is load-bearing
    * exactly once: at the fork identifier. Both of the other cases are points
    * at which two nodes can disagree about validity. This one is not.
    *
    * The discriminator that separates them is **not** *"does this change the
    * rules"*, which is the natural guess and is wrong. EIP-2124 settles it in
    * its own worked example, which enumerates the checksum for one network
    * fork by fork (`ethereum/EIPs` @
    * `9c915ee494c05069945f4e1018fa0854e2d3fb38`, 2026-08-14): the sequence runs
    * from the genesis hash to `uint64(1150000)` to `uint64(1920000)`. The
    * second of those is the DAO fork, which EIP-779 describes as changing no
    * opcode, transaction format or block structure -- **present**. The upgrade
    * at block 200,000, which enforced nothing, is **absent**, and no entry
    * stands between the two.
    *
    * `ethereum/go-ethereum` @ `6bb0588ad8e7f922e4ad5580f51265a4097af08f`
    * implements it that way independently, by reflecting over the
    * block-numbered fields of the chain configuration (`core/forkid/forkid.go`,
    * `gatherForks`): the DAO fork has such a field and an upgrade of this case
    * has none, so its own vectors move from `0x97c2c34c` to `0x91d1f948` at
    * block 1,920,000 and never move at 200,000 (`core/forkid/forkid_test.go`).
    *
    * So a projection keyed on [[ProtocolChange]] would drop the DAO fork, and
    * one keyed on every entry would keep this. Neither is right, and no
    * two-valued type can express the difference.
    *
    * ==What this case is NOT==
    *
    * Not an upgrade whose effect was small, and not one whose effect is
    * unrecorded. The network may change observably here. What puts it in this
    * case is that **nothing a node validates differs across it** -- the change
    * is one participants chose, within bounds the protocol already allowed and
    * does not alter.
    */
  case Unenforced

/** One upgrade on one network's schedule: when it activates, what that network
  * calls it, and what it does.
  *
  * The three are separate on purpose. An upgrade that gates no rule is an entry
  * whose spec is the one already in force -- it has a name and an activation
  * and changes nothing, which is a state every canonical fork enumeration in
  * the field contains and which a schedule keyed on rule changes alone could
  * not represent without renumbering everything after it.
  */
final case class ScheduleEntry(activation: Activation, id: UpgradeId, upgrade: Upgrade)

/** One network's upgrades, in order, with the rules in force at any point
  * derivable from them.
  *
  * ==Authored, not derived from another network==
  *
  * Every entry is written for this network. The field reuses one network's fork
  * by inheriting from another's -- `besu-eth/besu-etc` at `eb4248c997` builds
  * its Tangerine Whistle from Ethereum mainnet's *Homestead* definition, so its
  * graph re-parents at the point the two networks diverge -- because
  * inheritance is the only reuse those clients have. Here the reuse is the
  * component vocabulary instead, so a schedule references no other network and
  * encodes no claim about which chain continued which.
  *
  * ==Total, because the genesis entry is required==
  *
  * [[at]] answers for every height and timestamp rather than returning an
  * option, and what makes that honest is [[Schedule.of]] refusing a schedule
  * whose first scheduled entry is not a rule set at block zero. **That entry is
  * this network's [[baseline]]** -- the rules it starts from -- which differs
  * per network and is the reason this module exists rather than the machine
  * holding one network's starting configuration for everybody.
  *
  * ==Order is checked, never imposed==
  *
  * The entries are kept as authored. Sorting them would silently accept the
  * schedule that a check refuses, and the order among entries sharing an
  * activation is meaningful -- EIP-2124 contemplates several upgrades at one
  * point, and which of them states the rules is then the author's to say.
  */
final class Schedule private (
    val network: Network,
    val entries: Vector[ScheduleEntry],
    val baseline: ProtocolSpec
):

  private val scheduled: Vector[ScheduleEntry] = entries.filter(_.activation.point.isDefined)

  /** The rules in force for a block at this height with this timestamp.
    *
    * ==It stops at the first upgrade that has not activated==
    *
    * Rather than taking the last one that has. The two answers differ only for
    * a block that could not exist -- a low height with a late timestamp -- and
    * on that input the second silently returns rules from the far side of a
    * fork the chain has not reached. Stopping at the first gap is what makes
    * the ordering invariant do work rather than merely be true.
    *
    * An entry that does not state rules leaves the answer alone, whether it
    * mutates state or does nothing at all. Those two are indistinguishable
    * here and are separated by [[forkPoints]].
    */
  def at(number: UInt64, timestamp: UInt64): ProtocolSpec =
    scheduled
      .takeWhile(entry => Schedule.hasActivated(entry.activation, number, timestamp))
      .foldLeft(baseline) { (held, entry) =>
        entry.upgrade match
          case Upgrade.ProtocolChange(spec) => spec
          case Upgrade.IrregularStateChange => held
          case Upgrade.Unenforced           => held
      }

  /** The activations at which this network's validity can diverge.
    *
    * ==What consumes this, and why it is derived here rather than there==
    *
    * EIP-2124 identifies a chain by a checksum over the activation points that
    * have passed, so that two peers whose rules will disagree find out at the
    * handshake instead of at a block. **Which points go into it is a property
    * of the schedule, and the wrong answer is silent** -- the checksum is still
    * a number, every peer still gets one, and the ones that reject it look like
    * unrelated network trouble.
    *
    * A client keyed on configuration fields gets the answer for free: an
    * upgrade it does not enforce simply has no field to reflect over. A
    * schedule has no such accident available, because every entry here carries
    * a real activation whether or not anything is enforced at it. So the
    * exclusion is made once, here, and the match below is exhaustive -- a case
    * added to [[Upgrade]] stops this compiling rather than defaulting into
    * either answer.
    *
    * Two exclusions, each with a different reason:
    *
    *   - [[Upgrade.Unenforced]], because nothing a node validates differs
    *     across it. That case's own documentation carries the evidence.
    *   - The entry at block zero, because EIP-2124 says so directly: *"If a
    *     chain is configured to start with a non-Frontier ruleset already in
    *     its genesis, that is NOT considered a fork."*
    *     `ethereum/go-ethereum` @ `6bb0588ad8e7f922e4ad5580f51265a4097af08f`
    *     drops it in the same words (`core/forkid/forkid.go`: *"Skip any forks
    *     in block 0, that's the genesis ruleset"*).
    *
    * ==One residual the schedule cannot discharge==
    *
    * That same client also drops timestamp activations at or before the genesis
    * block's own timestamp, and a schedule does not hold the genesis block. A
    * caller computing an identifier has to apply that filter itself. It cannot
    * bite on a network whose upgrades to date are all by block number.
    *
    * The activations are returned with their axes rather than as bare numbers:
    * EIP-6122 keeps the two lists separate, and [[Activation.point]] documents
    * why flattening is the checksum's step and not this one.
    */
  def forkPoints: Vector[Activation] =
    scheduled
      .filter(entry => Schedule.divergesAt(entry.upgrade))
      .map(_.activation)
      .filter {
        case Activation.AtBlock(number) => number != UInt64.Zero
        case _                          => true
      }
      .distinct

object Schedule:

  /** Why a set of entries is not a schedule. */
  enum Error:

    /** Nothing here activates anywhere, so no rules are ever in force. */
    case NoScheduledEntry

    /** The earliest scheduled entry does not activate at block zero, so there
      * are heights the schedule cannot answer for.
      */
    case MissingGenesis(first: Activation)

    /** The entry at block zero changes state rather than stating rules, so the
      * network has no starting rule set.
      */
    case GenesisWithoutRules(id: UpgradeId)

    /** An entry activates before the one written above it. */
    case OutOfOrder(previous: Activation, offending: UpgradeId)

    /** An entry activates by block number after one that activates by
      * timestamp, which EIP-6122 § Additional rules forbids: *"Forks by
      * timestamp MUST be scheduled at or after the forks by block"*.
      */
    case TimestampBeforeBlock(previous: Activation, offending: UpgradeId)

    /** Entries from two networks, which is how one network's rules reach the
      * other under a shared label.
      */
    case MixedNetworks(expected: Network, found: Network)

    /** Two entries the network calls the same thing. */
    case DuplicateUpgrade(id: UpgradeId)

  private val byPoint: Ordering[UInt64] = summon[Ordering[UInt64]]

  /** The schedule these entries form, or the first reason they do not.
    *
    * The checks run in the order a reader would want them reported: which
    * network this is, then whether the entries are distinct, then whether there
    * is a starting rule set, then whether the order holds.
    *
    * ==This is the only way to build one, and that assumption has a trigger==
    *
    * The class constructor is private, so every invariant above holds of every
    * [[Schedule]] that exists. **A derived decoder does not go through here.**
    * Scala's structural derivation builds a product from its fields directly,
    * so a `Mirror` for a type reaches past a private constructor and past every
    * check written beside it. That is a property of the language rather than an
    * observation about this build: `Mirror.Product.fromProduct` takes a plain
    * `scala.Product` and populates the fields, so scoping a constructor narrows
    * who may *call* it and not who may *build* the value.
    *
    * Nothing in this build derives structurally today: `derives`,
    * `Mirror.ProductOf`, `deriveDecoder` and circe's generic derivation are each
    * absent from every module, and all 28 decoders are hand-written. **The
    * trigger is the first time a chain configuration is decoded by derivation
    * rather than by hand** -- a natural thing to want of a configuration type,
    * and the moment every validated constructor in this module stops being the
    * only door. Hand-write the decoder, or re-establish these checks on the far
    * side of it.
    */
  def of(entries: Vector[ScheduleEntry]): Either[Error, Schedule] =
    for
      network <- entries.headOption.map(_.id.network).toRight(Error.NoScheduledEntry)
      _ <- oneNetwork(network, entries)
      _ <- distinctUpgrades(entries)
      scheduled = entries.filter(_.activation.point.isDefined)
      genesis <- scheduled.headOption.toRight(Error.NoScheduledEntry)
      baseline <- startsAtGenesis(genesis)
      _ <- ordered(scheduled)
    yield new Schedule(network, entries, baseline)

  private def oneNetwork(expected: Network, entries: Vector[ScheduleEntry]): Either[Error, Unit] =
    entries
      .map(_.id.network)
      .find(_ != expected)
      .toLeft(())
      .left
      .map(found => Error.MixedNetworks(expected, found))

  private def distinctUpgrades(entries: Vector[ScheduleEntry]): Either[Error, Unit] =
    val ids = entries.map(_.id)
    ids.zipWithIndex
      .collectFirst { case (id, index) if ids.indexOf(id) < index => id }
      .toLeft(())
      .left
      .map(Error.DuplicateUpgrade.apply)

  private def startsAtGenesis(genesis: ScheduleEntry): Either[Error, ProtocolSpec] =
    genesis.activation match
      case Activation.AtBlock(number) if number == UInt64.Zero =>
        genesis.upgrade match
          case Upgrade.ProtocolChange(spec) => Right(spec)
          case Upgrade.IrregularStateChange => Left(Error.GenesisWithoutRules(genesis.id))
          case Upgrade.Unenforced           => Left(Error.GenesisWithoutRules(genesis.id))
      case first => Left(Error.MissingGenesis(first))

  private def ordered(scheduled: Vector[ScheduleEntry]): Either[Error, Unit] =
    scheduled
      .sliding(2)
      .collectFirst {
        case Vector(earlier, later) if !Activation.activationOrdering.lteq(earlier.activation, later.activation) =>
          if earlier.activation.axis.contains(Activation.Axis.Timestamp) &&
            later.activation.axis.contains(Activation.Axis.Block)
          then Error.TimestampBeforeBlock(earlier.activation, later.id)
          else Error.OutOfOrder(earlier.activation, later.id)
      }
      .toLeft(())

  /** Whether two nodes can disagree about validity across this upgrade.
    *
    * Exhaustive on purpose: this is the one question whose answer cannot be
    * defaulted, and a new case must be decided rather than inherited.
    */
  private def divergesAt(upgrade: Upgrade): Boolean = upgrade match
    case Upgrade.ProtocolChange(_)    => true
    case Upgrade.IrregularStateChange => true
    case Upgrade.Unenforced           => false

  private def hasActivated(activation: Activation, number: UInt64, timestamp: UInt64): Boolean =
    activation match
      case Activation.AtBlock(at)     => byPoint.lteq(at, number)
      case Activation.AtTimestamp(at) => byPoint.lteq(at, timestamp)
      case Activation.Unscheduled     => false
      case Activation.Never           => false
