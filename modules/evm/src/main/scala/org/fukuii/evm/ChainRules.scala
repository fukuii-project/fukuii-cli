package org.fukuii.evm

/** A change one proposal makes to the rules a chain runs.
  *
  * A function rather than a record of what changed, because the changes are not
  * one shape: an addition to the operation table, a price moved in the schedule
  * and a behavior settled by a flag have nothing in common except that each
  * turns one set of rules into another.
  */
type Proposal = ChainRules => ChainRules

/** The rules one chain runs, as a value a fork produces rather than a branch the
  * machine takes.
  *
  * ==A fork is a value here, and asking which fork is active is the failure==
  *
  * [[OpcodeTable]] already states this for operations -- *"nothing here asks
  * which fork is active, because by the time a table exists that question has
  * been answered"* -- and a behavior that varies by fork is the first thing that
  * would have broken the commitment, because a table cannot hold it and a
  * schedule cannot price it. So it is held here, beside them, and the machine
  * reads a value exactly as it reads the other two.
  *
  * ==The field settled the representation, and the clients disagree==
  *
  * Surveyed for the first behavior change this had to carry, a creation that
  * cannot pay to store its code:
  *
  *   - the executable specification duplicates the whole fork package;
  *   - go-ethereum and its proof-of-work line branch inside the machine on a
  *     fork-named boolean, `chainRules.IsHomestead`;
  *   - core-geth branches inside the machine too, but on a per-proposal
  *     predicate, `IsEnabled(GetEIP2Transition, blockNumber)`, because it serves
  *     several networks from one binary;
  *   - besu carries a `requireCodeDepositToSucceed` field on a processor its
  *     fork constructs, and branches nowhere.
  *
  * **The last is the shape adopted**, because it is the only one that leaves the
  * machine free of fork names -- which is the commitment above -- and because
  * this project already hands the machine its operations and its prices as
  * values, so a behavior is the third of a kind rather than a new mechanism.
  * **core-geth's vocabulary is adopted with it**: a flag is named for the rule
  * it settles, never for the fork that shipped it, so the multi-network reading
  * stays available.
  *
  * ==Deltas, and why they must not rewrite what they do not name==
  *
  * A fork is [[Baseline]] with a sequence of proposals applied. That shape is
  * core-geth's, which starts from a base instruction set and applies a flat
  * sequence of per-proposal activations, keeping fork names only in comments.
  * **A proposal that alters anything it does not name is the seam failing**, and
  * the fields left alone survive as the same values rather than as equal copies
  * -- which is what makes that testable rather than merely intended.
  *
  * @param codeDepositMustSucceed
  *   whether a creation that cannot pay to store its returned code fails. At the
  *   baseline it does not: the account is left with no code, the gas already
  *   spent stays spent, and the creating operation is told the address as though
  *   code had been stored. EIP-2 reverses this.
  */
final case class ChainRules(
    table: OpcodeTable,
    schedule: GasSchedule,
    precompiles: PrecompileSet,
    codeDepositMustSucceed: Boolean
):

  /** These rules with each proposal applied, in the order given.
    *
    * Order is the caller's to state and is not always free: two proposals
    * touching one price compose to whichever ran last.
    */
  def applying(proposals: Proposal*): ChainRules =
    proposals.foldLeft(this)((held, change) => change(held))

object ChainRules:

  /** The rules the machine started with.
    *
    * Named for what it is rather than for the fork that shipped it, following
    * [[OpcodeTable.baseline]] and the client that had the same problem: a
    * baseline shared by every network cannot carry one network's name for it.
    */
  val Baseline: ChainRules =
    ChainRules(
      table = OpcodeTable.baseline(GasSchedule.Baseline),
      schedule = GasSchedule.Baseline,
      precompiles = PrecompileSet.baseline(GasSchedule.Baseline),
      codeDepositMustSucceed = false
    )

/** The changes individual proposals make to the rules a chain runs.
  *
  * Named for the proposal rather than the fork that shipped it, so a network
  * that adopts one without the other -- which is the ordinary case across the
  * families this project serves -- can say so.
  */
object Proposals:

  /** EIP-7 -- `DELEGATECALL`.
    *
    * An addition to the table and nothing else. What the operation *does* is the
    * machine's; that it exists at all is this.
    */
  val delegateCall: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.DelegateCall, Cost.Computed)))
