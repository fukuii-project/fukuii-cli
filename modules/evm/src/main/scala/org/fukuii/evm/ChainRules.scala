package org.fukuii.evm

/** A change one proposal makes to the rules a chain runs.
  *
  * A function rather than a record of what changed, because the changes are not
  * one shape: an addition to the operation table, a price moved in the schedule
  * and a behavior settled by a flag have nothing in common except that each
  * turns one set of rules into another.
  */
type Proposal = ChainRules => ChainRules

/** How much of what a caller has left a nested invocation is given.
  *
  * ==Two arguments, and the baseline ignores the first==
  *
  * That is the whole content of the shape. `remaining` is what the caller would
  * still hold once the invocation's own price and its memory are paid;
  * `requested` is what the operation asked for. A rule that caps nothing cannot
  * be written as the identity over `remaining` -- that would compare the request
  * against what is left and hand back the smaller, turning a caller that asks
  * for more than it holds from a frame that runs out of gas into one that
  * quietly succeeds with less. **The comparison exists only where a cap does**,
  * so a baseline able to ignore `remaining` outright is what the second argument
  * buys. besu's baseline reaches the same conclusion from the other side: it
  * hands back the request and compares nothing.
  *
  * ==Contract==
  *
  * `remaining` is never negative. Its callers subtract a price from what a frame
  * holds, and a frame that cannot cover that price passes zero rather than the
  * difference -- so the clamp has one home, at the subtraction, rather than one
  * here and one there.
  *
  * Both arguments are gas, so transposing them compiles and is wrong. The order
  * is `(remaining, requested)`.
  */
type GasForwarding = (BigInt, BigInt) => BigInt

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
  * ==A member that is a function changes what comparing two of these means==
  *
  * [[gasForwarded]] compares by reference rather than by value, so two rules
  * built separately are unequal even where the same proposals produced them.
  * What the seam actually claims is untouched: applying no proposal returns the
  * same value, and a field no proposal names survives as the same value rather
  * than as an equal copy. A test comparing whole rules built twice is the one
  * that would be surprised.
  *
  * @param gasForwarded
  *   how much of what the caller has left a nested invocation is given, out of
  *   what it asked for. At the baseline it is given what it asked for and the
  *   caller pays for all of it, so a request larger than the caller can cover is
  *   a frame that runs out of gas. EIP-150 caps it, and the same request then
  *   succeeds with less. It reaches every nested invocation, `CREATE` included.
  * @param codeDepositMustSucceed
  *   whether a creation that cannot pay to store its returned code fails. At the
  *   baseline it does not: the account is left with no code, the gas already
  *   spent stays spent, and the creating operation is told the address as though
  *   code had been stored. EIP-2 reverses this.
  * @param signatureSMustBeLow
  *   whether a signature whose `s` exceeds half the curve order is refused. At
  *   the baseline it is not, and both values of an `s` and its mirror image
  *   recover the same account under two different transaction hashes. EIP-2
  *   refuses the upper half. **This one is settled outside the machine**, by
  *   whatever admits a transaction, and is held here because it is the fork's
  *   rule and not that layer's preference.
  */
final case class ChainRules(
    table: OpcodeTable,
    schedule: GasSchedule,
    precompiles: PrecompileSet,
    gasForwarded: GasForwarding,
    codeDepositMustSucceed: Boolean,
    signatureSMustBeLow: Boolean
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
      gasForwarded = (_, requested) => requested,
      codeDepositMustSucceed = false,
      signatureSMustBeLow = false
    )

  /** The baseline with EIP-2 and EIP-7 applied.
    *
    * A fork name labels the composition; the proposals it is composed of carry
    * neutral names, which is the split [[Proposals]] exists to keep. Both
    * network families this project targets ran these two together and shared
    * their history to this point, so one name serves both.
    *
    * `lazy` because [[Proposals]] is a sibling in this file: a strict value here
    * would fix an initialisation order between two top-level objects for no
    * reason anyone reading either would expect.
    */
  lazy val Homestead: ChainRules =
    Baseline.applying(
      Proposals.delegateCall,
      Proposals.creationCharge,
      Proposals.codeDepositMustSucceed,
      Proposals.lowSignatureS
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

  /** EIP-2 -- a transaction that deploys pays a surcharge before it runs.
    *
    * A repricing in place: the field exists at the baseline priced at nothing,
    * so this moves a number and changes no shape. **It is only observable on a
    * transaction whose recipient is absent**, which is the path the harness
    * could not execute at all until it was taught to.
    */
  val creationCharge: Proposal =
    rules => rules.copy(schedule = rules.schedule.copy(transactionCreate = BigInt(32000)))

  /** EIP-2 -- a deployment that cannot pay to store its code fails outright.
    *
    * A behavior and neither an entry nor a price, which is the delta kind that
    * forced the rules to be a value at all. The baseline leaves the account
    * behind holding nothing and keeps the gas; this undoes the creation and
    * takes it.
    */
  val codeDepositMustSucceed: Proposal = _.copy(codeDepositMustSucceed = true)

  /** EIP-2 -- a signature whose `s` is above half the curve order is refused.
    *
    * The comparison is strict in the specification, so `s` exactly at half the
    * order stays valid and only what is above it is refused. Settled outside the
    * machine, which is why it is a rule here and a check there.
    */
  val lowSignatureS: Proposal = _.copy(signatureSMustBeLow = true)

  /** EIP-150 -- the operations that read account state cost more.
    *
    * Four prices move and nothing else does. What the four have in common is a
    * state read rather than a computation, which is the proposal's own
    * reasoning: loading a storage slot, reading an account's balance, reading
    * another account's code, and reaching another account at all were priced
    * against arithmetic rather than against the disk they touch.
    *
    * ==A settled price has two homes, and moving one of them is silent==
    *
    * [[OpcodeTable.baseline]] copies a settled price out of the schedule when it
    * builds an entry, so an operation charged through that entry is charged what
    * the table was built with rather than what the schedule holds now. A
    * proposal that moved the schedule alone would leave `BALANCE`, `EXTCODESIZE`
    * and `SLOAD` charging exactly what they charged before, with nothing in the
    * schedule to show it. So this names the table too, which is what
    * [[OpcodeTable.adding]] is for: an entry inserted over an operation already
    * present replaces it.
    *
    * `EXTCODECOPY` and the call family read their settled part from the schedule
    * at the moment they spend it and need no entry. [[GasSchedule.externalBase]]
    * is therefore read from both places at once -- the table for `EXTCODESIZE`,
    * the schedule for `EXTCODECOPY` -- which is why one field moving has to
    * reach both.
    */
  val stateReadRepricing: Proposal =
    rules =>
      val repriced = rules.schedule.copy(
        storageLoad = BigInt(200),
        balance = BigInt(400),
        externalBase = BigInt(700),
        callBase = BigInt(700)
      )
      rules.copy(
        schedule = repriced,
        table = rules.table
          .adding(Operation(Opcode.SLoad, Cost.Fixed(repriced.storageLoad)))
          .adding(Operation(Opcode.Balance, Cost.Fixed(repriced.balance)))
          .adding(Operation(Opcode.ExtCodeSize, Cost.Fixed(repriced.externalBase)))
      )

  /** EIP-150 -- a nested invocation is given all but one sixty-fourth of what
    * the caller has left.
    *
    * The proposal's own reason is not the gas: it is that a caller asking for
    * more than it holds used to be a frame that ran out of gas, and contracts
    * computing their request from what they held were written against prices
    * this same proposal moves. Capping rather than refusing keeps those callers
    * working, and the sixty-fourth left behind is what the caller then has to
    * act on whatever came back.
    *
    * ==One rule serves `CREATE` as well, and that is arithmetic rather than
    * economy==
    *
    * A creation names no request; it forwards everything it holds. So its
    * request and what remains are the same number, and capping either is the
    * same operation -- which is why this reaches four operations through one
    * member rather than needing a second for the one that asks for nothing.
    */
  val forwardedGasCap: Proposal =
    _.copy(gasForwarded = (remaining, requested) => requested.min(remaining - remaining / 64))
