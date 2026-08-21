package org.fukuii.evm

/** A change one proposal makes to the rules a chain runs.
  *
  * A function rather than a record of what changed, because the changes are not
  * one shape: an addition to the operation table, a price moved in the schedule
  * and a behavior settled by a flag have nothing in common except that each
  * turns one set of rules into another.
  */
type Proposal = EvmRules => EvmRules

/** How much of what a caller has left a nested invocation is given.
  *
  * ==Data rather than a function, and that is what makes rules comparable==
  *
  * This was a `(BigInt, BigInt) => BigInt`, which is the shape the arithmetic
  * suggests and the wrong shape for the thing it sits in. Two consequences, and
  * the second is the one that bites:
  *
  *   - **Equality on a function is unspecified.** A lambda capturing nothing
  *     may or may not be the same instance from one evaluation to the next, so
  *     [[EvmRules]] could not be compared as a whole while one of its members
  *     was one -- and *"do these two networks run the same rules"* is a
  *     question this project has to answer.
  *   - **A later proposal could replace this rule by accident.** Written
  *     `_.copy(gasForwarded = ...)`, a body that ignores what it found is a
  *     REPLACEMENT and a body that calls it is a REFINEMENT, and nothing at the
  *     call site distinguishes them. As data, a proposal that wants to refine
  *     rather than replace cannot express itself without adding a case here,
  *     which is a deliberate act rather than a silent one.
  *
  * ==Two arguments, and the whole rule ignores the first==
  *
  * `remaining` is what the caller would still hold once the invocation's own
  * price and its memory are paid; `requested` is what the operation asked for.
  * A rule that caps nothing cannot be written as the identity over `remaining`
  * -- that would compare the request against what is left and hand back the
  * smaller, turning a caller that asks for more than it holds from a frame that
  * runs out of gas into one that quietly succeeds with less. **The comparison
  * exists only where a cap does**, so a rule able to ignore `remaining`
  * outright is what the second argument buys. besu's uncapped gas calculator
  * reaches the same conclusion from the other side: it hands back the request
  * and compares nothing.
  *
  * ==Contract==
  *
  * `remaining` is never negative. Its callers subtract a price from what a
  * frame holds, and a frame that cannot cover that price passes zero rather
  * than the difference -- so the clamp has one home, at the subtraction, rather
  * than one here and one there.
  *
  * Both arguments are gas, so transposing them compiles and is wrong. The order
  * is `(remaining, requested)`.
  *
  * ==Postcondition: `0 <= result <= remaining`==
  *
  * A rule may hand back no more than the caller has left. Both cases this build
  * ships satisfy it by construction -- one returns its second argument, which
  * its caller has already bounded, and the other takes a minimum against it --
  * so nothing turns on it today. **The reason to state it is the shape of the
  * rule that would break it**: a floor, of the kind that gives a callee a
  * minimum to work with, returns a figure that does not depend on `remaining`
  * at all, and extending this enum is exactly what such a proposal would do.
  *
  * Both sites enforce it rather than trusting it, and neither could be told to
  * do otherwise: each charges what it was handed, and a charge refuses rather
  * than taking a frame's gas below nothing.
  */
enum GasForwarding:

  /** The invocation is given what it asked for, whatever the caller has left.
    *
    * A request larger than the caller can cover is then a frame that runs out
    * of gas rather than one that succeeds with less.
    */
  case Whole

  /** The invocation is given what it asked for, capped at all but one
    * sixty-fourth of what the caller has left.
    *
    * ==The name and the figure are both the ecosystem's==
    *
    * `besu-eth/besu` @ `c2addd9424` supplies the name --
    * `allButOneSixtyFourth(value) = value - value / 64` -- and
    * `ethereum/go-ethereum` @ `6bb0588ad` and `ethereumclassic/core-geth` @
    * `4185df450` write the same arithmetic as `gas - gas/64` in
    * `core/vm/gas.go`. **No surveyed client parameterizes the fraction**, and
    * neither does this.
    *
    * ==A network that chose differently adds a case==
    *
    * That is a deliberate act, which is the property this type is already built
    * around: a proposal cannot refine what a predecessor set without extending
    * the enum, and a network wanting another fraction is the same shape of
    * change. Every such case arrives with its own arithmetic written out, so
    * there is no quantity here for a caller to supply and none to be wrong.
    *
    * **That is what makes the invalid state unrepresentable rather than
    * guarded.** A held divisor would be a bare `Int` in a consensus slot, and
    * no scoping of its constructor closes structural construction -- so the
    * value would stay reachable however carefully the constructor were
    * restricted, and the guard would be a claim to keep true rather than a
    * property the compiler enforces.
    */
  case AllButOneSixtyFourth

  /** What a nested invocation is given, out of what it asked for. */
  def forward(remaining: BigInt, requested: BigInt): BigInt = this match
    case Whole                => requested
    case AllButOneSixtyFourth => requested.min(remaining - remaining / 64)

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
  * A fork is a network's starting configuration with a sequence of proposals
  * applied. That shape is core-geth's, which starts from a base instruction set
  * and applies a flat sequence of per-proposal activations, keeping fork names
  * only in comments. **A proposal that alters anything it does not name is the
  * seam failing**, and the fields left alone survive as the same values rather
  * than as equal copies -- which is what makes that testable rather than merely
  * intended.
  *
  * **What the starting configuration IS does not live here.** The machine holds
  * no privileged set of rules: a network's genesis configuration is that
  * network's, and this module would otherwise be handing every later network a
  * first network's choices to be a delta from.
  *
  * ==Comparing two of these IS a value comparison, and each member had to earn
  * that==
  *
  * *"Do these two networks run the same rules"* is a question this project has
  * to answer -- two networks sharing a history agree through the fork where
  * they part, and that is a test rather than a comment. A record answers by
  * value only when every member does, and three of these did not: two plain
  * classes with no equality of their own, and a function, whose equality the
  * language does not settle at all.
  *
  * All three were fixed rather than worked around, because the alternative was
  * a comparison that answered by value on some members and by reference on
  * others -- which is worse than one that answers by reference throughout. Such
  * a comparison returns *different* for two identical configurations built
  * separately, and *same* for two references to one build, so its answer is
  * decided by how a caller happened to construct its inputs.
  * [[PrecompileSet.equals]] carries the one residual and why its direction is
  * the safe one.
  *
  * **The seam's own claim is still asserted with `eq`, deliberately.** That a
  * proposal leaves untouched fields as the SAME value rather than an equal copy
  * is a stronger statement than equality, and only reference identity says it.
  *
  * @param gasForwarded
  *   how much of what the caller has left a nested invocation is given, out of
  *   what it asked for. Where a network caps nothing the invocation is given
  *   what it asked for and the caller pays for all of it, so a request larger
  *   than the caller can cover is a frame that runs out of gas. EIP-150 caps
  *   it, and the same request then succeeds with less. It reaches every nested
  *   invocation, `CREATE` included.
  * @param codeDepositMustSucceed
  *   whether a creation that cannot pay to store its returned code fails. Where
  *   a network has not adopted EIP-2 it does not: the account is left with no
  *   code, the gas already spent stays spent, and the creating operation is told
  *   the address as though code had been stored. EIP-2 reverses this.
  */
final case class EvmRules(
    table: OpcodeTable,
    schedule: GasSchedule,
    precompiles: PrecompileSet,
    gasForwarded: GasForwarding,
    codeDepositMustSucceed: Boolean
):

  /** These rules with each proposal applied, in the order given.
    *
    * Order is the caller's to state and is not always free: two proposals
    * touching one price compose to whichever ran last.
    */
  def applying(proposals: Proposal*): EvmRules =
    proposals.foldLeft(this)((held, change) => change(held))
