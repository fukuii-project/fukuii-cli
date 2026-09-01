package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.Proposal
import org.fukuii.execution.AdmissionRules
import org.fukuii.types.TransactionType

/** EIP-2930 -- a transaction may declare what it will reach, pay for the
  * declaration, and reach it at the reduced price.
  *
  * ==Two facets, and the document is the first proposal here whose halves
  * cannot be composed independently==
  *
  * Admission gains a format; the machine gains two prices for what declaring
  * costs. The two are disjoint as DELTAS -- one writes
  * `org.fukuii.execution.AdmissionRules.admittedTypes` and the other two fields
  * of the schedule -- so their order is immaterial. What is not immaterial is
  * that [[Eip2929]] is composed too: the declaration's whole benefit is that it
  * pre-warms that document's sets, and a network taking this one alone would
  * charge for a declaration that buys nothing. The document says so in its
  * frontmatter, `requires: 2718, 2929`.
  *
  * ==The two figures==
  *
  * `ACCESS_LIST_ADDRESS_COST` 2400 and `ACCESS_LIST_STORAGE_KEY_COST` 1900, from
  * the document's own Parameters table (`ethereum/EIPs` @ `dbfa6bee8`,
  * `EIPS/eip-2930.md`, Final). `ethereum/execution-specs` @ `20f7f6271` states
  * them at `forks/berlin/vm/gas.py:84-85` as `TX_ACCESS_LIST_ADDRESS` and
  * `TX_ACCESS_LIST_STORAGE_KEY`, and `besu-eth/besu` @ `fdf1247c6` a third time
  * at `BerlinGasCalculator.java:44,47`.
  *
  * ==DUPLICATES ARE CHARGED AND NOT DEDUPLICATED==
  *
  * *"Note that non-unique addresses and storage keys are not disallowed, though
  * they will be charged for multiple times, and aside from the higher gas cost
  * there is no other difference in execution flow or outcome from
  * multiple-inclusion of a value as opposed to the recommended
  * single-inclusion."* The same field seeds a SET of what may then be reached
  * cheaply, so one declaration is read two ways, and
  * `org.fukuii.execution.IntrinsicGas` carries that split at the site that
  * charges it.
  *
  * ==What the format costs beyond admitting it: three types had to grow==
  *
  * The charge is intrinsic, so it is compared against the transaction's limit
  * before anything runs. `org.fukuii.execution.OfferedTransaction` and
  * `AdmittedTransaction` carry the declaration for that reason, and
  * `IntrinsicGas.of` takes it as a term. **Without them this delta would admit
  * the format and undercharge it** -- a transaction whose limit covered only the
  * base and its data would be settled where the network refuses it, which is not
  * a refusal difference but a state root.
  *
  * ==What it does NOT reach==
  *
  * The signature rules. `org.fukuii.execution.AdmissionRules.signatureMayCarryChainId`
  * governs whether a legacy `v` may fold a chain identifier in, and this format
  * states its chain as a field -- so nothing here touches it, and nothing here
  * touches the bound on `s` either. The recovery path already routes every
  * tagged payload through its own preimage.
  *
  * The envelope. That is [[Eip2718]]'s, adopted beside this one, and this
  * document supplies the first payload to arrive in it.
  */
object Eip2930:

  /** What declaring an account and a slot ahead of running costs. */
  val declarationPricing: Proposal =
    rules =>
      rules.copy(schedule =
        rules.schedule.copy(
          transactionAccessListAddress = BigInt(2400),
          transactionAccessListStorageKey = BigInt(1900)
        )
      )

  /** The format a block at these rules may carry, added to what it already
    * carried.
    *
    * Added rather than replaced: the untagged format stays valid, and a network
    * that refused it here would refuse every transaction it had ever carried.
    */
  val admitsDeclaringFormat: AdmissionRules => AdmissionRules =
    rules => rules.copy(admittedTypes = rules.admittedTypes + TransactionType.AccessList)

  /** Adopting the document, which is adopting both of its deltas.
    *
    * Built from the general constructor rather than the machine-scoped one,
    * because one of the two halves is admission's.
    */
  val component: Component =
    Component(
      ProposalId.Eip(2930),
      rules =>
        rules.copy(
          evm = rules.evm.applying(declarationPricing),
          admission = admitsDeclaringFormat(rules.admission)
        )
    )
