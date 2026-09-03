package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal

/** EIP-3541 -- a leading byte no deployment may store.
  *
  * ==One byte, reserved at every creation path at once==
  *
  * *"After `block.number == HF_BLOCK` new contract creation (via create
  * transaction, `CREATE` or `CREATE2` instructions) results in an exceptional
  * abort if the code's first byte is `0xEF`."* (`ethereum/EIPs` @ `dbfa6bee`,
  * `EIPS/eip-3541.md`, Final). The document names all three creation paths in
  * one sentence, and `org.fukuii.evm.Interpreter.deploy` is the one body all
  * three reach -- its own note already records that deployment has two entry
  * points and that copying the rule into the layer above would put a
  * fork-varying rule in two places.
  *
  * ==It reserves the byte; it does not define it==
  *
  * The document is explicit that `0xEF` remains an undefined instruction and
  * keeps aborting when executed, so nothing here touches the table. What changes
  * is only whether code beginning with that byte may be STORED. A reader
  * expecting an opcode entry beside this component will not find one, and that
  * absence is the document's.
  *
  * ==The refusal costs everything, which is what makes it an exceptional abort
  * rather than a rejection==
  *
  * *"The exceptional abort due to code starting with `0xEF` behaves exactly the
  * same as any other exceptional abort that can occur during initcode execution,
  * i.e. in case of abort all gas provided to a `CREATE*` or create transaction
  * is consumed."* That is why it is refused where the over-long deployment is
  * refused, and why it reaches the same reversal.
  *
  * ==Held as the byte rather than as a flag==
  *
  * `org.fukuii.evm.EvmRules.reservedCodePrefix` carries `0xEF` as data, for the
  * reason the bound beside it carries its own figure rather than a flag. No
  * client parameterizes the byte -- `ethereum/go-ethereum-pow` @ `v1.10.26`
  * compares `ret[0] == 0xEF` behind a fork test, `besu-eth/besu` @ `fdf1247c6d`
  * installs a `PrefixCodeRule`, and `ethereum/execution-specs` @ `20f7f6271a`
  * compares inline in the fork module that has it -- so this is a choice about
  * where the constant lives rather than a reading of the field.
  *
  * ==Ethereum Classic takes this document, and takes it without the four beside
  * it==
  *
  * `ethereumclassic/core-geth` @ `4185df450` sets `EIP3541FBlock` at 14,525,000
  * in `params/config_classic.go`, under a comment reading *"London (partially),
  * aka Mystique"*, alongside EIP-3529 and nothing else from this upgrade. The
  * governing document is ECIP-1104, which records a reason for each omission:
  * the fee market *"would conflict with the current monetary policy set in
  * ECIP-1017"*, the base-fee operation *"depends on EIP-1559"*, and the bomb
  * delay is *"not applicable to ETC due to difficulty bomb being defused"*.
  *
  * **That document cites the bomb delay as EIP-3228, and no such proposal
  * exists.** The sentence it quotes is EIP-3554's own summary, and the number it
  * most plausibly garbles is EIP-3238 -- itself struck from this upgrade months
  * before that document was written. The omission is right and its citation is
  * not; cite EIP-3554.
  */
object Eip3541:

  /** The byte the document reserves.
    *
    * Named rather than written into the delta so that the value and the rule
    * that reads it are stated once each.
    */
  val ReservedPrefix: Int = 0xef

  /** Deployment refuses code beginning with the reserved byte. */
  val reserveCodePrefix: Proposal =
    rules => rules.copy(reservedCodePrefix = Some(ReservedPrefix))

  /** Adopting the document, which is adopting its one delta. */
  val component: Component = Component.evm(ProposalId.Eip(3541), reserveCodePrefix)
