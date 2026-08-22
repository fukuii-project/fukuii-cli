package org.fukuii.execution

import org.fukuii.types.TransactionType

/** What makes a transaction acceptable at all, as a value a fork produces
  * rather than a branch the check takes.
  *
  * ==Separate from the machine's rules, because it is settled before the
  * machine runs==
  *
  * Everything here is decided about a transaction that has not executed and may
  * never execute: a refusal here leaves the world exactly as it was. That is
  * what makes it a different facet rather than more fields on the machine's
  * rules, and it is the boundary `besu-eth/besu` @ `c2addd9424` draws too --
  * in `MainnetProtocolSpecs.java` its transaction-validator builder is set nine
  * times against three for the block-processor builder, and two of those three
  * are one historical state recovery. **Admission varies by fork more than
  * execution does**, which is the opposite of what the two names suggest.
  *
  * ==A rule here is the fork's, never the admitting layer's preference==
  *
  * The distinction matters because the same layer also holds policy an operator
  * may tune -- what a node is willing to relay, what price it insists on --
  * and none of that belongs here. A member of this record is one a block
  * containing the transaction is valid or invalid by, so two nodes disagreeing
  * about it disagree about the chain.
  *
  * @param admittedTypes
  *   the transaction formats a block at these rules may carry. A format the set
  *   does not hold is refused before anything else is read of the transaction,
  *   which is why it is a set of formats rather than a bound on a tag number:
  *   [[org.fukuii.types.Transaction]] decodes every format on every network so
  *   that a malformed transaction and a well-formed one this network does not
  *   carry are distinguishable, and it names this layer as the one that decides
  *   between them. `besu-eth/besu` @ `c2addd9424` holds the same set under the
  *   same shape, as `MainnetTransactionValidator`'s
  *   `Set<TransactionType> acceptedTransactionTypes`, and refuses on
  *   `!acceptedTransactionTypes.contains(transactionType)`.
  * @param signatureSMustBeLow
  *   whether a signature whose `s` exceeds half the curve order is refused.
  *   Before EIP-2 it is not, and an `s` and its mirror image then recover the
  *   same account under two different transaction hashes -- a duplicate the
  *   curve cannot suppress and only a fork can refuse. The comparison is strict
  *   in the proposal, so an `s` exactly at half the order stays valid and only
  *   what is above it is refused.
  */
final case class AdmissionRules(
    admittedTypes: Set[TransactionType],
    signatureSMustBeLow: Boolean
)
