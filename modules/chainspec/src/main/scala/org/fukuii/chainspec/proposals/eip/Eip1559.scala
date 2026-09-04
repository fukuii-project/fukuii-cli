package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.UInt256
import org.fukuii.chainspec.{Component, FeeMarket, HeaderRules, ProposalId}
import org.fukuii.execution.AdmissionRules
import org.fukuii.types.TransactionType

/** EIP-1559 -- a charge the block sets, and a format that pays it.
  *
  * ==Two facets move, and the second is a facet nothing wrote before==
  *
  * The admission half is [[Eip2930]]'s shape exactly: a format added to what a
  * block already carried, never replacing it, because a network refusing the
  * untagged format would refuse every transaction it had ever carried.
  *
  * The header half is this document's alone. It is the first entry ever written
  * to [[org.fukuii.chainspec.HeaderRules]], and that facet exists because of
  * this proposal -- [[org.fukuii.chainspec.UpgradeRules]] forecast it and stated
  * the condition it could not be built under, that *"a facet with no consumer is
  * a shape chosen against no evidence"*. `org.fukuii.consensus.HeaderValidator`
  * is the consumer, and it and this landed together.
  *
  * ==The three values, each read from two sources that do not derive from one
  * another==
  *
  * `ethereum/execution-specs` @ `20f7f6271a` states them in
  * `forks/london/fork.py` at `:73`, `:74` and `:76`;
  * `ethereum/go-ethereum-pow` @ `v1.10.26` states the same three as package
  * constants in `params/protocol_params.go`. `besu-eth/besu` @ `fdf1247c6d`
  * agrees on all three in `LondonFeeMarket.java` and additionally treats the
  * opening charge as a value a chain may set at genesis, which is why
  * [[org.fukuii.chainspec.FeeMarket]] carries it as a member rather than as a
  * literal in the derivation.
  *
  * ==What this document does NOT write, and each for its own reason==
  *
  * **No gas-limit rule.** The elasticity multiplier below is what scales a
  * parent's limit at the one block a market begins, and the bound it is scaled
  * for is not fork-resolved on any network this project serves -- so the
  * validator holds the bound and reads the multiplier from here.
  *
  * **No destination for the charge.** What a block charges is not moved to any
  * account, so there is no address to configure; the burn is an omission at
  * settlement rather than a transfer, and
  * `org.fukuii.execution.TransactionProcessor` is where that is visible. **A
  * network that ROUTED the charge instead would need a member here**, and it
  * would be a consensus change with a governing proposal of its own rather than
  * a configuration of this one.
  *
  * **No opcode.** Reading the charge from inside the machine is [[Eip3198]],
  * which is a separate document in the same upgrade and depends on this one.
  */
object Eip1559:

  /** The fee market a block under these rules runs. */
  val feeMarket: HeaderRules => HeaderRules =
    _.copy(feeMarket =
      Some(
        FeeMarket(
          initialBaseFee = UInt256
            .fromBigInt(BigInt(1000000000))
            .getOrElse(throw new IllegalStateException("the opening charge does not fit a header field")),
          elasticityMultiplier = BigInt(2),
          maxChangeDenominator = BigInt(8)
        )
      )
    )

  /** The format a block at these rules may carry, added to what it already
    * carried.
    */
  val admitsCappedFormat: AdmissionRules => AdmissionRules =
    rules => rules.copy(admittedTypes = rules.admittedTypes + TransactionType.DynamicFee)

  /** Adopting the document, which is adopting both of its deltas.
    *
    * Built from the general constructor rather than the machine-scoped one:
    * neither half reaches the machine, and the one part of this upgrade that
    * does is [[Eip3198]].
    */
  val component: Component =
    Component(
      ProposalId.Eip(1559),
      rules =>
        rules.copy(
          admission = admitsCappedFormat(rules.admission),
          header = feeMarket(rules.header)
        )
    )
