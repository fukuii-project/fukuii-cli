package org.fukuii.chainspec.proposals.eip

import org.fukuii.chainspec.{Component, ProposalId}
import org.fukuii.evm.{Cost, Opcode, Operation, Proposal}

/** EIP-1884 -- repricing the operations whose cost tracks the size of the trie,
  * and one new operation that avoids paying it.
  *
  * ==Three prices and one addition, all four from the Specification==
  *
  * *"The `SLOAD` (`0x54`) operation changes from `200` to `800` gas"*, *"The
  * `BALANCE` (`0x31`) operation changes from `400` to `700` gas"*, *"The
  * `EXTCODEHASH` (`0x3F`) operation changes from `400` to `700` gas"* and *"A
  * new opcode, `SELFBALANCE` is introduced at `0x47` ... `SELFBALANCE` is priced
  * as `GasFastStep`, at `5` gas"* (`ethereum/EIPs` @ `dbfa6bee`,
  * `EIPS/eip-1884.md`, Final). Corroborated at `ethereum/execution-specs` @
  * `20f7f6271a`, `forks/istanbul/vm/gas.py` -- `SLOAD` at `:40`,
  * `OPCODE_BALANCE` at `:160`, `OPCODE_EXTCODEHASH` at `:116`, and
  * `FAST_STEP: Final[Uint] = Uint(5)` at `:57`, which is what
  * `forks/istanbul/vm/instructions/environment.py` charges `SELFBALANCE`.
  *
  * ==`GasFastStep` IS the low tier, and the corroborating source carries a
  * second constant of the same value that is not it==
  *
  * This entry is built from `org.fukuii.evm.GasSchedule.low`, a tier rather
  * than a figure of this operation's own, for the reason [[Eip1344]] gives --
  * an operation priced at a tier moves when the tier does, and a literal opts
  * it out. The identity is established rather than inferred from the number:
  * `ethereum/go-ethereum` @ `e9e35a42f`, `core/vm/gas.go:25-30` declares the
  * ladder, and the `GasFastStep = 5` this document names is the same constant
  * `MUL`, `DIV`, `SDIV`, `MOD`, `SMOD` and `SIGNEXTEND` are priced from.
  *
  * **`forks/istanbul/vm/gas.py` ALSO declares `LOW: Final[Uint] = Uint(5)` at
  * `:35`, and that is NOT what `SELFBALANCE` is charged.** Two constants, one
  * value, different identity -- so checking this entry against the tier that
  * carries the tier's own name reaches the right number by the wrong route, and
  * would go on agreeing if only one of them ever moved. [[Eip1052]] records the
  * same shape against a figure that matched `externalBase` by coincidence.
  *
  * ==`SLOAD_GAS` reaches THREE fields of this schedule and only ONE of them is
  * this document's==
  *
  * The 800 above is the price of the `SLOAD` OPERATION, which is
  * `org.fukuii.evm.GasSchedule.storageLoad`. The same 800 also appears inside
  * the `SSTORE` calculation, where it is
  * `org.fukuii.evm.GasSchedule.netStorageNoop` and
  * `org.fukuii.evm.GasSchedule.netStorageDirty` -- and those two are
  * [[Eip2200]]'s to move, from that document's own `SLOAD_GAS` definition. The
  * specification carries one constant and this schedule carries three fields,
  * so the two documents each move their own.
  *
  * **Moving one set and not the other produces a schedule that compiles, runs,
  * and charges a storage read one price through `SLOAD` and another through
  * `SSTORE`.** Nothing compares them, so nothing would report it.
  *
  * ==Two of the three prices are read ONLY where the table is built==
  *
  * So repricing the schedule alone would leave `SLOAD`, `BALANCE` and
  * `EXTCODEHASH` charging their old figures with a record that says otherwise.
  * The entries are rebuilt for that reason -- `org.fukuii.evm.GasSchedule`
  * states the classes and how to re-derive which field is in which, and
  * [[Eip1052]] records this hazard against `extCodeHash` at the point that field
  * was introduced, naming this document as the repricing that would meet it.
  *
  * ==What it does NOT reach==
  *
  * `EXTCODESIZE` and `EXTCODECOPY`, which are priced from
  * `org.fukuii.evm.GasSchedule.externalBase` and already stand at 700. This
  * document brings `BALANCE` and `EXTCODEHASH` up to the figure `EXTCODESIZE`
  * already carried, rather than moving the whole family.
  *
  * **Its Rationale and its Specification disagree about `EXTCODEHASH`, and the
  * Specification is the one to follow.** Arguing for the `BALANCE` increase the
  * document calls it *"comparable to `EXTCODESIZE` and `EXTCODEHASH`, which are
  * priced at `700` already"* -- but `EXTCODEHASH` stood at 400 going into this
  * fork, which is what the Specification quoted above moves. Reading the
  * Rationale as normative would leave that operation unrepriced at 400, and
  * `forks/istanbul/vm/gas.py:116` settles it at 700.
  *
  * And the precompile set, and every storage figure other than the `SLOAD`
  * price.
  */
object Eip1884:

  /** The three operations that walk a trie whose size they do not control cost
    * more, in the record and in the entries built from it alike.
    */
  val trieSizeRepricing: Proposal =
    rules =>
      val repriced = rules.schedule.copy(
        storageLoad = BigInt(800),
        balance = BigInt(700),
        extCodeHash = BigInt(700)
      )
      rules.copy(
        schedule = repriced,
        table = rules.table
          .adding(Operation(Opcode.SLoad, Cost.Fixed(repriced.storageLoad)))
          .adding(Operation(Opcode.Balance, Cost.Fixed(repriced.balance)))
          .adding(Operation(Opcode.ExtCodeHash, Cost.Fixed(repriced.extCodeHash)))
      )

  /** Reading the invocation's OWN balance takes no operand and no lookup the
    * caller has to pay `BALANCE`'s new price for.
    *
    * The document's reason for the tier is that this is still a trie read and so
    * is not the cheapest tier: *"the EVM execution engine still needs a lookup
    * into the (cached) trie, and `balance`, unlike `gasPrice` or `timeStamp`, is
    * not constant during the execution, so it has a bit more inherent
    * overhead."*
    */
  val selfBalance: Proposal =
    rules => rules.copy(table = rules.table.adding(Operation(Opcode.SelfBalance, Cost.Fixed(rules.schedule.low))))

  /** Adopting the document, which is adopting both of its deltas. */
  val component: Component = Component.evm(ProposalId.Eip(1884), trieSizeRepricing, selfBalance)
