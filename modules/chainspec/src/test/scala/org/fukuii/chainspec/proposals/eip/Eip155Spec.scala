package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.{Address, Bytes, UInt256, UInt64}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.execution.{Refusal, TransactionAdmission}
import org.fukuii.types.Transaction
import org.scalatest.flatspec.AnyFlatSpec

/** What adopting EIP-155 changes, asserted through the component and then
  * through the layer that reads it.
  *
  * ==Through [[Eip155.component]], because the wiring is what is untested==
  *
  * The delta is one assignment and is reachable on its own, so a spec calling
  * it directly passes with the component wired to nothing. The recorded
  * proposal list cannot stand in for it either: `UpgradeRules.adopting` rebuilds
  * that list from the identifiers it was passed, so it reads the same whatever
  * the delta did.
  *
  * ==And then through the refusal, because a flag nothing reads is not a rule==
  *
  * The document's whole observable effect is which answer a transaction naming
  * a chain gets, and the case below is the one that separates the two answers
  * without a valid signature: a `v` naming ANOTHER network is refused for its
  * signature before this document and for its chain after it, and both branches
  * short-circuit ahead of recovery. What that refusal means, and why the two
  * rules are read in that order, is
  * [[org.fukuii.execution.TransactionAdmission.senderOf]]'s to state.
  */
class Eip155Spec extends AnyFlatSpec:

  private val base: UpgradeRules = ethereum.Upgrades.tangerineWhistle

  private val adopted: UpgradeRules = base.adopting(Eip155.component)

  private def quantity(value: BigInt): UInt256 =
    UInt256.fromBigInt(value).getOrElse(fail("a fixture quantity does not fit a machine word"))

  /** Ethereum Classic's registered identifier, which is not this network's.
    *
    * Read from EIP-155 § *List of Chain ID's* (`ethereum/EIPs` @
    * `15f61ed0fda82ec86d8d6a872f6b874816f03d96`), the same table
    * [[ethereum.Mainnet.network]] takes 1 from.
    */
  private val anotherChain: BigInt = BigInt(61)

  /** A transaction whose `v` names [[anotherChain]] under this document's own
    * encoding, `{0,1} + CHAIN_ID * 2 + 35`.
    *
    * Its `r` and `s` are placeholders and never read: both branches under test
    * answer before recovery is reached, which is what lets this case exist
    * without a curve operation.
    */
  private val namingAnotherChain: Transaction.Legacy =
    Transaction.Legacy(
      nonce = UInt64.Zero,
      gasPrice = UInt256.Zero,
      gasLimit = UInt64.fromBits(21000L),
      to = None,
      value = UInt256.Zero,
      data = Bytes.Empty,
      v = quantity(anotherChain * 2 + 35),
      r = quantity(BigInt(1)),
      s = quantity(BigInt(1))
    )

  private def verdict(rules: UpgradeRules): Either[Refusal, Address] =
    TransactionAdmission.senderOf(namingAnotherChain, ethereum.Mainnet.network.chainId, rules.admission)

  "adopting EIP-155" should "admit a legacy signature that names a chain" in
    assert(
      adopted.admission.signatureMayCarryChainId,
      "the rule permitting the later signing scheme did not reach the admission facet"
    )

  it should "not have been admitted before it was adopted" in
    // The control. The member is a boolean, so a case asserting the adopted
    // value alone would pass against rules that already held it.
    assert(
      !base.admission.signatureMayCarryChainId,
      "the preceding rules already admitted the scheme this document introduces"
    )

  it should "reach no facet outside admission" in
    assert(
      (adopted.evm eq base.evm) && (adopted.execution eq base.execution) &&
        (adopted.consensus eq base.consensus),
      "a rule about which signatures are readable altered the machine, settlement or consensus"
    )

  "a transaction naming another network" should "be refused for its signature before this document" in
    // Not for its chain. Below the proposal there is no scheme under which a
    // `v` above the two parities means anything, so the network it named is
    // never reached -- which is what both the specification and the clients
    // answer, and what the ordering inside `senderOf` exists to preserve.
    assert(
      verdict(base) == Left(Refusal.InvalidSignature),
      "the earlier rules read a chain out of a signature they do not admit at all"
    )

  it should "be refused for its chain after it" in
    // The whole observable difference the document makes, at the layer that
    // makes it. A component that set the flag on a facet nothing read would
    // pass every case above and fail this one.
    assert(
      verdict(adopted) == Left(Refusal.WrongChainId),
      "the rule set adopting EIP-155 still refuses a chain-naming signature as malformed"
    )
