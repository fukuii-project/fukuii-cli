package org.fukuii.chainspec

/** Which of the published difficulty-adjustment algorithms a fork runs.
  *
  * ==One expression, three multipliers, which is the proposal's own framing==
  *
  * `ethereum/EIPs` @ `9c915ee494c05069945f4e1018fa0854e2d3fb38`, EIP-2 (Final),
  * states its fourth specification item as a replacement of one formula by
  * another and writes both in the same shape: a parent difficulty, plus that
  * difficulty over a bound divisor, multiplied by a figure taken from how long
  * the block took. Only the multiplier differs, so this enumerates the
  * multiplier and nothing else.
  *
  * ==A value rather than the function itself, and that is forced==
  *
  * [[UpgradeRules]] states that a member typed as a function or an open
  * interface puts two identically-configured networks into unequal rule sets,
  * and *"do these two networks run the same rules"* is a question this project
  * has to answer. `besu-eth/besu-etc` @ `eb4248c99` does carry the function --
  * `DifficultyCalculator difficultyCalculator` on `ProtocolSpec.java` -- which
  * is the shape unavailable here. The other three surveyed clients already
  * carry a value: `NethermindEth/nethermind` @ `c35ce1b1a` resolves
  * `IsEip2Enabled` and `IsEip100Enabled` on `IReleaseSpec`,
  * `ethereumclassic/core-geth` @ `4185df450` branches on
  * `GetEthashEIP100BTransition` then `GetEIP2Transition`, and
  * `openethereum/openethereum` @ `v3.0.1` on `homestead_transition` then
  * `eip100b_transition`.
  *
  * ==Three cases and not two booleans==
  *
  * The pair those clients carry admits four states where three exist -- EIP-100
  * without EIP-2 is not a state any network reaches -- and
  * `.claude/rules/scala3-style.md` asks for the type that admits exactly the
  * states the domain has. A match over this reports where it has to be extended
  * when a fourth arrives.
  *
  * ==The cases are proposal numbers because this record is shared across
  * families==
  *
  * `.claude/rules/nomenclature.md` reserves a family's fork names for that
  * family's own leaf, and both families resolve this facet. The field's gate
  * vocabulary is already proposal-numbered in three of the four clients above,
  * even where each names its own *calculator* after a fork.
  */
enum DifficultyAdjustment:

  /** The rule in force before any proposal changed it: the parent's difficulty
    * moved by one step of itself, up or down, on whether the block took less
    * than a fixed number of seconds.
    *
    * EIP-2 writes it as
    * *"`parent_diff + parent_diff // 2048 * (1 if block_timestamp -
    * parent_timestamp < 13 else -1)`"* when quoting what it replaces, which is
    * the same two-valued rule
    * `ethereum/execution-specs` @ `ccaaaba58` computes in
    * `forks/frontier/fork.py` as a `max_adjustment_delta` added or subtracted.
    *
    * **Named for what it is rather than for the fork that ran it**, following
    * `org.fukuii.evm.OpcodeTable.original`, which is this project's word for
    * the state a proposal is a change to.
    */
  case Original

  /** EIP-2's replacement: a signed offset taken from the gap between the block
    * and its parent, floored so that a very long gap cannot collapse the
    * difficulty.
    *
    * The two-valued rule became continuous here, which is the material
    * difference between the two and the reason this enumeration exists rather
    * than a single formula parameterized by a divisor.
    */
  case Eip2

  /** EIP-100's replacement for that: the same offset, taken over a shorter
    * interval, and raised by one step where the parent itself included ommers.
    *
    * The parent's ommers enter the calculation here for the first time, which
    * is why the executable specification's own signature grows a parameter at
    * this fork and not before -- `parent_has_ommers` is absent from
    * `forks/homestead/fork.py` and present in `forks/byzantium/fork.py` at
    * `ethereum/execution-specs` @ `ccaaaba58`.
    */
  case Eip100
