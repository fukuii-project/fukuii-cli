package org.fukuii.chainspec

/** What one network calls one of its upgrades.
  *
  * ==A label never determines the rules==
  *
  * This is the invariant the type exists to hold, and it is not a caution: it
  * is a measured property of the field. `NethermindEth/nethermind` at
  * `c35ce1b1ab` carries six classes under `Nethermind.Specs/GnosisForks/`, each
  * named for an Ethereum upgrade with `Gnosis` appended, and
  * `12_LondonGnosis.cs` is that network's London built from Ethereum's and then
  * given a fee collector -- because one network burns the base fee and the
  * other routes it. **Gnosis's London is not Ethereum's London, and the two
  * share a name.**
  *
  * So an identifier here carries the network it belongs to, two identifiers
  * from different networks are never equal however they are spelled, and
  * nothing anywhere maps one of these to a rule set. What a network runs is
  * decided by [[UpgradeRules]] and the components that produced it; this says
  * only what that network's own documents call the moment it started running
  * them.
  *
  * ==Scoped by network, not by network family==
  *
  * Two networks in one family run the same compositions at different
  * activations -- a test network is its mainnet's rule sets on its own
  * schedule -- so their upgrades share a name and are still different entries
  * with different activations. Scoping by [[Network]] says that; scoping by
  * family would not, and there is no registered identifier for a family to
  * scope by in any case.
  *
  * ==Nothing here records where a name came from==
  *
  * A network naming its upgrade with a word another network coined is that
  * network's name, used in its own specification documents, and is treated as
  * any other name would be. No surveyed client models the provenance of a
  * label, and modelling it would invite exactly the reading the first section
  * forbids -- that two labels sharing an origin share something else.
  *
  * The one distinction the type does draw is [[UpgradeId.Label.Synthesized]],
  * and it is not provenance: it is the case where the network supplied no name
  * to record.
  *
  * @param label
  *   the network's word for the upgrade, or the marker that it never had one.
  */
final case class UpgradeId(network: Network, label: UpgradeId.Label):

  /** The form a reader sees, always carrying the network.
    *
    * Two networks' upgrades sharing a word render differently, which is the
    * disambiguation the first section of this type's documentation is about.
    */
  def show: String = label match
    case UpgradeId.Label.Named(text) => s"${network.name} $text"
    case UpgradeId.Label.Synthesized => s"${network.name} Genesis"

object UpgradeId:

  /** Whether the network named this upgrade itself. */
  enum Label:

    /** The network's own word for it, from the network's own documents. */
    case Named(text: String)

    /** The network never named this configuration.
      *
      * ==Measured, and it is the launching configuration every time==
      *
      * Gnosis's own canonical genesis (`gnosischain/configs` at `e542d13`,
      * `mainnet/genesis.json`) declares 35 parameters, 28 of them per-proposal,
      * and **no fork-named key at all**; the earliest document under that
      * project's `network-upgrades/` is Constantinople, which the network
      * launched before. Two independent clients therefore label its genesis
      * with an Ethereum upgrade name -- `NethermindEth/nethermind` at
      * `c35ce1b1ab` maps its genesis block to `Byzantium.Instance`
      * (`GnosisSpecProvider.cs`).
      *
      * A borrowed label would read as that network's own and it is not; an
      * empty string would read as an omission. This is the third answer: the
      * configuration is real, it has rules, and the network gave it no name --
      * so one is composed from the network's, in one form for every network
      * that needs it rather than a different hand-written string per schedule.
      */
    case Synthesized

  def named(network: Network, text: String): UpgradeId = UpgradeId(network, Label.Named(text))

  def synthesized(network: Network): UpgradeId = UpgradeId(network, Label.Synthesized)
