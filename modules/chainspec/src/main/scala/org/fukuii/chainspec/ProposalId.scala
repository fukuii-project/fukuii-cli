package org.fukuii.chainspec

/** One improvement proposal, named by its series and its number.
  *
  * ==The series is load-bearing, because bare numbers collide==
  *
  * `1015` is a document in two series at once: `EIP-1015` is *Configurable On
  * Chain Issuance*, and `ECIP-1015` is *Long-term gas cost changes for IO-heavy
  * operations to mitigate transaction spam attacks*. A component list holding
  * bare integers could not tell them apart, and the two are not remotely the
  * same rule. So the series travels with the number rather than being inferred
  * from whichever network the list belongs to.
  *
  * ==Why the marker sits HERE and not on a schedule entry==
  *
  * Because the two levels genuinely differ, and the field demonstrates it on one
  * activation. Ethereum Classic's gas reprice at block 2,500,000 is
  * `"ecip1015Block": 2500000` to `besu-eth/besu-etc` at `eb4248c997`
  * (`config/src/main/resources/classic.json`) and
  * `EIP150Block: big.NewInt(2500000)` to `ethereumclassic/core-geth` at
  * `4185df450` (`params/config_classic.go`). Its removal of the difficulty bomb
  * at 5,900,000 is `ecip1041Block` to the first and `DisposalBlock` to the
  * second. **Both readings are right at their own level**: the RULE is EIP-150,
  * and the DOCUMENT by which that network adopted it is ECIP-1015. The rule's
  * series belongs to the component; the network's own word for the upgrade
  * belongs to [[UpgradeId]], which carries no series at all.
  *
  * ==The set is open==
  *
  * A network that authors its own series adds a case here. Two are declared
  * because two are used; nothing about the shape assumes there will only ever be
  * two, and a case added later makes every exhaustive match over this type
  * report where it has to be extended.
  */
enum ProposalId:

  /** An Ethereum improvement proposal. */
  case Eip(number: Int)

  /** An Ethereum Classic improvement proposal. */
  case Ecip(number: Int)

  /** The form the proposal is cited by, which is the one its own document
    * uses.
    */
  def show: String = this match
    case Eip(number)  => s"EIP-$number"
    case Ecip(number) => s"ECIP-$number"
