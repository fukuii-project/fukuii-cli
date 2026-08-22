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
  * @param signatureMayCarryChainId
  *   whether a legacy signature may fold a chain identifier into its `v`. EIP-155
  *   added the identifier to `v` rather than as a field, so the encoding is the
  *   only evidence a legacy transaction gives of which scheme signed it, and
  *   before that proposal a `v` naming anything but the two parities is refused.
  *   **The rule permits the later scheme; it does not require it.** Both remain
  *   valid afterwards, so this gates only the form that names a chain and the
  *   earlier form is admitted at every height on every network -- a rule reading
  *   the absence of an identifier as a refusal would refuse every transaction
  *   these forks ever carried. `ethereum/execution-specs` @ `ccaaaba58` states
  *   both halves by replacement rather than by a flag:
  *   `forks/frontier/transactions.py`'s `recover_sender` opens `if v != 27 and v
  *   != 28: raise InvalidSignatureError("bad v")`, and
  *   `forks/spurious_dragon/transactions.py` drops that line for a `chain_id`
  *   that answers `None` to 27 and 28 and parses anything else.
  *
  *   Two clients reach the refusal from the other side, which is what settles
  *   that it is the SIGNATURE this is refused by rather than the chain named:
  *   `ethereumclassic/core-geth` @ `4185df450` selects `HomesteadSigner` below
  *   the transition, whose `recoverPlain` subtracts 27 from `v` and requires the
  *   remainder to be 0 or 1, so a `v` naming a chain fails as `ErrInvalidSig`
  *   and never as `ErrInvalidChainId`; `NethermindEth/nethermind` @ `c35ce1b1ab`
  *   sends a `v` that is not 27 or 28 to `LegacySignatureTxValidator`, which
  *   answers `InvalidTxSignature` wherever `IsEip155Enabled` is unset.
  *
  *   **Named for what the signature may carry, not for "replay protection",
  *   though that is the proposal's own title.** `besu-eth/besu` @ `c2addd9424`
  *   spends that phrase on two different things -- `ProtocolSpec`'s
  *   `isReplayProtectionSupported`, which only its transaction pool reads, and
  *   `strictTxReplayProtectionEnabled`, an operator's choice about what to relay
  *   -- and the second is the policy this record's own contract excludes. The
  *   word is doubly spent here too: an ordered record of adoptions *replays* to
  *   a rule set, and a nonce is a count of increments to *replay*.
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
    signatureMayCarryChainId: Boolean,
    signatureSMustBeLow: Boolean
)
