package org.fukuii.chainspec

import org.fukuii.bytes.UInt64

/** What a scheduled upgrade does when it activates.
  *
  * ==Four answers, because the field needs all four==
  *
  * An upgrade states rules, or mutates state without touching the rules, or
  * does neither and is on the schedule because the network's own canonical
  * enumeration has it there, or states rules at a point nobody could have known
  * in advance.
  *
  * **Two independent questions separate them, not one.** *Does this change the
  * rules a node runs?* is answered by [[UpgradeSchedule.at]]. *Does EIP-2124
  * count this activation?* is answered by [[UpgradeSchedule.forkPoints]]. The
  * four cases are what happens when those two questions are allowed to disagree
  * -- and three of the four combinations occur in the field, which is why no
  * smaller type will do:
  *
  *   - rules AND counted -- [[RuleChange]], the ordinary hard fork.
  *   - no rules AND counted -- [[IrregularStateChange]].
  *   - no rules AND not counted -- [[Unenforced]].
  *   - rules AND NOT counted -- [[RetrospectiveRuleChange]], where the
  *     activation was a condition rather than a number.
  *
  * A reader looking for the fourth combination in the other direction -- no
  * rules, no state, and counted anyway -- will find it is real and is not here.
  * [[UpgradeSchedule.reachesForkIdentifier]] records where it occurs and what
  * would bring it in.
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
  * modeled**: it needs a layer that can mutate state, which does not exist,
  * and a nullary case is what forces whoever builds that layer to add the
  * payload deliberately rather than find a plausible field already waiting.
  *
  * ==The first case says "rule" where the quotation above says "protocol"==
  *
  * Deliberately, and the quotation is left exactly as its proposal wrote it,
  * because editing a citation to match local vocabulary falsifies it. The two
  * words are used for different things by the two authorities: the proposal
  * means the consensus rules, while six of six surveyed clients reserve
  * *protocol* for the wire protocol -- `eth/protocols` in go-ethereum, erigon,
  * core-geth and op-geth, `Network/P2P/Subprotocols` in nethermind,
  * `EthProtocol.java` in besu. A reader inside a client meets both senses and a
  * reader of the proposal meets one, so the client vocabulary is the one that
  * has to win here. What EIP-779 contrasts is kept intact either way: rules
  * against state, which is exactly [[RuleChange]] against
  * [[IrregularStateChange]].
  */
enum Upgrade:

  /** The rules the network runs from this activation onward. */
  case RuleChange(rules: UpgradeRules)

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
    * So a projection keyed on [[RuleChange]] would drop the DAO fork, and
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

  /** The rules the network runs from here, where the activation point was not
    * knowable in advance.
    *
    * ==Why this is not [[RuleChange]], when the rules change exactly as much==
    *
    * It differs in one thing only, and that thing is EIP-2124. A fork
    * identifier is a checksum a node computes over the activation points it has
    * passed and sends to a peer BEFORE either has the other's chain. A point
    * that could not be known ahead of time cannot go into it: a peer that has
    * not yet reached the condition has no number to checksum, so including it
    * would make two honest nodes compute different identifiers for the same
    * chain.
    *
    * **EIP-3675 states the requirement and the reason.** *"For the purposes of
    * the EIP-2124 fork identifier, nodes implementing this EIP MUST set the
    * `FORK_NEXT` parameter to the `FORK_NEXT_VALUE`"* (`ethereum/EIPs` @
    * `dbfa6bee8` (2026-08-26), `EIPS/eip-3675.md:147`), because *"the number of
    * `TRANSITION_BLOCK` cannot be known ahead of time given the dynamic nature
    * of the transition trigger condition. As the block will not be known a
    * priori, nodes can't use its number for `FORK_NEXT`"* (`:224`).
    *
    * ==The activation this case carries is a RETROSPECTIVE reading, and that is
    * the whole content==
    *
    * Once such a transition has happened, the height it happened at is an
    * ordinary number and a schedule can state it. `ethereum/execution-specs` @
    * `20f7f6271` (2026-08-26) does exactly that and says why in the same file:
    * `src/ethereum/forks/paris/__init__.py:39-43` notes that the trigger was a
    * condition on accumulated work, that the event *"is now a historical
    * event"*, and then states `ByBlockNumber(15537394)`.
    *
    * So the number is real, [[UpgradeSchedule.at]] must honour it, and only the
    * identifier must not count it. **That is one fact about one entry, which is
    * why it is a case here rather than a rule about which upgrades are special.**
    *
    * ==Four production clients agree, and one of them is shaped like this type==
    *
    * `NethermindEth/nethermind` @ `b92e2a471` (2026-08-26) is the closest: it
    * puts such an upgrade on its schedule and then excludes that one height from
    * the identifier by name (`Nethermind.Specs/MainnetSpecProvider.cs:76`,
    * `excludeBlocks: [ParisBlockNumber]`), documenting the parameter as being
    * for a point that is *"TTD-determined and not a fork-ID boundary"*.
    * `besu-eth/besu` @ `fdf1247c6` enumerates the fork block numbers by hand and
    * omits it. `ethereum/go-ethereum` @ `e9e35a42f` and `erigontech/erigon` @
    * `776a380b1` reflect over configuration fields, and the transition has no
    * field of the shape they read.
    *
    * ==What this case is NOT, and the distinction is a different entry==
    *
    * Not the *"virtual fork"* a network may configure alongside such a
    * transition to split the network at a knowable height. That is EIP-3675's
    * `FORK_NEXT_VALUE` and it is a separate upgrade at a separate height: on
    * `Sepolia` the transition is at 1,450,409 and the virtual fork at 1,735,371,
    * **284,962 blocks apart** (`erigontech/erigon` @ `776a380b1`,
    * `execution/chain/spec/chainspecs/sepolia.json`, `mergeBlock` against
    * `mergeNetsplitBlock`). That second entry DOES reach the identifier and
    * changes no rules -- the mirror of this case, and one this type does not
    * model. See [[UpgradeSchedule.reachesForkIdentifier]] for the trigger.
    */
  case RetrospectiveRuleChange(rules: UpgradeRules)

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
  * option, and what makes that honest is [[UpgradeSchedule.of]] refusing a
  * schedule whose first scheduled entry is not a rule set at block zero.
  * **That entry is this network's [[genesisRules]]** -- the rules it starts from --
  * which differs per network and is the reason this module exists rather than
  * the machine holding one network's starting configuration for everybody.
  *
  * ==Order is checked, never imposed==
  *
  * The entries are kept as authored. Sorting them would silently accept the
  * schedule that a check refuses, and the order among entries sharing an
  * activation is meaningful -- EIP-2124 contemplates several upgrades at one
  * point, and which of them states the rules is then the author's to say.
  */
final class UpgradeSchedule private (
    val network: Network,
    val entries: Vector[UpgradeSchedule.Entry],
    val genesisRules: UpgradeRules
):

  private val scheduled: Vector[UpgradeSchedule.Entry] = entries.filter(_.activation.point.isDefined)

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
  def at(number: UInt64, timestamp: UInt64): UpgradeRules =
    scheduled
      .takeWhile(entry => UpgradeSchedule.hasActivated(entry.activation, number, timestamp))
      .foldLeft(genesisRules) { (held, entry) =>
        entry.upgrade match
          case Upgrade.RuleChange(rules)              => rules
          case Upgrade.RetrospectiveRuleChange(rules) => rules
          case Upgrade.IrregularStateChange           => held
          case Upgrade.Unenforced                     => held
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
    * Three exclusions, each with a different reason:
    *
    *   - [[Upgrade.Unenforced]], because nothing a node validates differs
    *     across it. That case's own documentation carries the evidence.
    *   - [[Upgrade.RetrospectiveRuleChange]], because its activation was not
    *     knowable in advance and EIP-3675 requires it be left out. That case's
    *     own documentation carries the evidence, and note that this exclusion
    *     is the only one of the three that drops an entry which DOES change the
    *     rules.
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
      .filter(entry => UpgradeSchedule.reachesForkIdentifier(entry.upgrade))
      .map(_.activation)
      .filter {
        case Activation.AtBlock(number) => number != UInt64.Zero
        case _                          => true
      }
      .distinct

object UpgradeSchedule:

  /** One upgrade on one network's schedule: when it activates, what that
    * network calls it, and what it does.
    *
    * The three are separate on purpose. An upgrade that gates no rule is an
    * entry whose rules are the ones already in force -- it has a name and an
    * activation and changes nothing, which is a state every canonical fork
    * enumeration in the field contains and which a schedule keyed on rule
    * changes alone could not represent without renumbering everything after it.
    */
  final case class Entry(activation: Activation, id: UpgradeId, upgrade: Upgrade)

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
    * [[UpgradeSchedule]] that exists. **A derived decoder does not go through
    * here.**
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
  def of(entries: Vector[Entry]): Either[Error, UpgradeSchedule] =
    for
      network <- entries.headOption.map(_.id.network).toRight(Error.NoScheduledEntry)
      _ <- oneNetwork(network, entries)
      _ <- distinctUpgrades(entries)
      scheduled = entries.filter(_.activation.point.isDefined)
      genesis <- scheduled.headOption.toRight(Error.NoScheduledEntry)
      genesisRules <- startsAtGenesis(genesis)
      _ <- ordered(scheduled)
    yield new UpgradeSchedule(network, entries, genesisRules)

  private def oneNetwork(expected: Network, entries: Vector[Entry]): Either[Error, Unit] =
    entries
      .map(_.id.network)
      .find(_ != expected)
      .toLeft(())
      .left
      .map(found => Error.MixedNetworks(expected, found))

  private def distinctUpgrades(entries: Vector[Entry]): Either[Error, Unit] =
    val ids = entries.map(_.id)
    ids.zipWithIndex
      .collectFirst { case (id, index) if ids.indexOf(id) < index => id }
      .toLeft(())
      .left
      .map(Error.DuplicateUpgrade.apply)

  private def startsAtGenesis(genesis: Entry): Either[Error, UpgradeRules] =
    genesis.activation match
      case Activation.AtBlock(number) if number == UInt64.Zero =>
        genesis.upgrade match
          case Upgrade.RuleChange(rules) => Right(rules)
          // Rules are rules: this case differs from the one above only at the
          // identifier, and genesis reaches no identifier under any reading --
          // EIP-2124 excludes block zero outright. So the answer here is the
          // rules, and reading this case as "special" and refusing it would
          // reject a schedule that states a starting rule set.
          case Upgrade.RetrospectiveRuleChange(rules) => Right(rules)
          case Upgrade.IrregularStateChange           => Left(Error.GenesisWithoutRules(genesis.id))
          case Upgrade.Unenforced                     => Left(Error.GenesisWithoutRules(genesis.id))
      case first => Left(Error.MissingGenesis(first))

  private def ordered(scheduled: Vector[Entry]): Either[Error, Unit] =
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

  /** Whether EIP-2124 counts this upgrade's activation.
    *
    * ==The name is deliberate, and the obvious one is now FALSE==
    *
    * This asked *"whether two nodes can disagree about validity across this
    * upgrade"* until [[Upgrade.RetrospectiveRuleChange]] existed, and that
    * reading was exact while every rule change reached the identifier. It is
    * not exact any more: two nodes plainly CAN disagree across a retrospective
    * rule change -- it is a rule change -- and this answers `false` for it.
    *
    * Keeping the old name would have left the next reader concluding that such
    * an upgrade changes no rules, which is the opposite of true and is a
    * conclusion the type is otherwise careful to prevent.
    *
    * ==Exhaustive on purpose==
    *
    * This is the one question whose answer cannot be defaulted, and a new case
    * must be decided rather than inherited. There is no `case _`, so a fifth
    * case stops the compile here.
    *
    * ==The mirror case is real, is in the field today, and is NOT modeled==
    *
    * Everything below answers `false` by changing nothing a peer needs to agree
    * about, or by being unknowable in advance. The reverse exists: an upgrade
    * that reaches the identifier while changing neither the rules nor the state.
    * EIP-3675 defines it as `FORK_NEXT_VALUE`, a *"virtual fork"* a network
    * configures at a knowable height so that peers split there instead of at a
    * transition nobody can predict.
    *
    * `Sepolia` has one: its transition is at 1,450,409 and its virtual fork at
    * 1,735,371, **284,962 blocks apart** (`erigontech/erigon` @ `776a380b1`
    * (2026-08-26), `execution/chain/spec/chainspecs/sepolia.json`, `mergeBlock`
    * against `mergeNetsplitBlock`). Three clients publish identical fork-id
    * vectors showing the identifier moving at the second height and not the
    * first.
    *
    * **No network authored in this build configures one, so it is not built.**
    * The trigger is the first that does. When it arrives, note that none of the
    * four cases fits it either: [[Upgrade.Unenforced]] is the tempting one and
    * is wrong, because it answers `false` here and such an entry must answer
    * `true`.
    */
  private def reachesForkIdentifier(upgrade: Upgrade): Boolean = upgrade match
    case Upgrade.RuleChange(_)              => true
    case Upgrade.IrregularStateChange       => true
    case Upgrade.Unenforced                 => false
    case Upgrade.RetrospectiveRuleChange(_) => false

  private def hasActivated(activation: Activation, number: UInt64, timestamp: UInt64): Boolean =
    activation match
      case Activation.AtBlock(at)     => byPoint.lteq(at, number)
      case Activation.AtTimestamp(at) => byPoint.lteq(at, timestamp)
      case Activation.Unscheduled     => false
      case Activation.Never           => false
