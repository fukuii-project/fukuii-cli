package org.fukuii.execution

/** What settling a transaction does around the machine, as a value a fork
  * produces rather than a branch the settlement takes.
  *
  * ==Rules, and deliberately not processors==
  *
  * The obvious contents of a facet with this name are a transaction processor
  * and a block processor, and the field says otherwise. `besu-eth/besu` @
  * `c2addd9424` is the only surveyed client modelling either as a member of a
  * fork-resolved specification at all, and its own definitions show how little
  * they vary: in `MainnetProtocolSpecs.java` the block-processor builder is set
  * three times against twenty-three for the gas calculator, and two of those
  * three are `daoRecoveryInitDefinition` and `daoRecoveryTransitionDefinition`
  * -- one historical event, plus a default. `NethermindEth/nethermind` @
  * `c35ce1b1ab` goes further and makes neither a specification member at all:
  * it carries a block processor and a transaction processor per network it
  * serves, AuRa, Optimism, Taiko and Xdc each having both, and selects between
  * them by which network module is wired in.
  *
  * So a processor varies by NETWORK and is chosen when a node is assembled,
  * while what varies by FORK is the handful of rules the processor consults.
  * Those are what this holds.
  *
  * ==Why a receipt's fork-varying content is here and not in a facet of its
  * own==
  *
  * A receipt is what settling a transaction produces, and no surveyed client
  * separates the two: `ethereum/go-ethereum` @ `6bb0588ad` decides the shape
  * inline in the execution path (`core/state_processor.go`), and besu carries
  * its receipt factory flat on the same specification as its transaction
  * processor rather than under any further grouping. A facet holding one
  * boolean that only the settlement path reads would be a layer the field does
  * not draw.
  *
  * ==Both members are named for the rule, never for the proposal or the fork==
  *
  * EIP-161 is why that distinction is not cosmetic here: it is four lettered
  * clauses, and only the last of them is this layer's -- the rest settle inside
  * the machine, at a creation's starting nonce and at what a call to a
  * non-existent account costs. Two clients keep the halves apart in their
  * configuration vocabulary rather than treating the document as one rule:
  * `ethereumclassic/core-geth` @ `4185df450` exposes `GetEIP161abcTransition`
  * and `GetEIP161dTransition` on its configurator interface, and
  * `NethermindEth/nethermind` @ `c35ce1b1ab` declares `Eip161abcTransition` and
  * `Eip161dTransition` as separate chain-spec parameters. **Neither activates
  * them apart in any configuration in this project's reference corpus**, so
  * what the split evidences is that the proposal is not one rule, not that a
  * network has run half of it.
  *
  * ==What is absent, and the one constraint that absence carries==
  *
  * A block's reward is not here. Where it is computed differs by consensus
  * mechanism rather than by fork, and the surveyed families disagree about it
  * more than about anything else in this record: several have none at all, one
  * redirects the beneficiary instead, and several compute it by calling a
  * contract against world state at the parent block. **So whatever slot it
  * eventually takes must admit an unbounded algorithmic schedule, must admit
  * nothing at all, and must not be typed as a function returning a number** --
  * and this record is deliberately not that slot.
  *
  * @param touchedEmptyAccountsAreDeleted
  *   whether an account touched while the transaction ran and left with no
  *   code, no balance and a zero nonce ceases to exist when the transaction
  *   settles. Where a network has not adopted EIP-161 it survives as an empty
  *   account and is committed to the state trie, so this is a state root
  *   difference on any transaction that touches one. `ethereum/execution-specs`
  *   @ `ccaaaba58` states it as a call to `destroy_touched_empty_accounts` from
  *   the fork's own transaction processing, and go-ethereum gates the same
  *   deletion on a member of its fork-resolved rules record.
  * @param receiptCarriesStatus
  *   whether a receipt's first field states that the transaction succeeded
  *   rather than the state root after it. EIP-658 replaces one with the other,
  *   and both forms remain live for a client that reads history from genesis,
  *   which is why `types`' own `PostStateOrStatus` carries both and states that
  *   which of them is correct at a given height belongs to the layer above it.
  *   This is that rule.
  */
final case class ExecutionRules(
    touchedEmptyAccountsAreDeleted: Boolean,
    receiptCarriesStatus: Boolean
)
