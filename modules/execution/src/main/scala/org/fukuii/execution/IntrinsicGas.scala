package org.fukuii.execution

import org.fukuii.bytes.Bytes
import org.fukuii.evm.{GasSchedule, Word}
import org.fukuii.types.AccessTuple

/** What a transaction is charged before any of it runs.
  *
  * ==Its own home, because two facets read it and neither owns it==
  *
  * Admission refuses a transaction whose limit cannot pay this charge, and
  * settlement subtracts it from that limit before the machine sees a unit of
  * gas. Those are the two facets of this module, so the charge sits beside both
  * rather than on either -- a copy on each would be one number with two
  * definitions, and a fork moving it would have to move both.
  *
  * **Admission is nonetheless the only caller, and that is the invariant rather
  * than a reason to fold this into it.** The charge is worked out once, where a
  * transaction is admitted, and handed to settlement on
  * [[AdmittedTransaction]]; settlement spends that figure and never asks for it
  * again. Moving this beside admission would put the number back inside the one
  * facet, where the other has to reach through a fork's rules to recover it.
  *
  * ==Every figure comes from the schedule, so a repricing is a value==
  *
  * Nothing here is a literal. `ethereum/execution-specs` @ `ccaaaba58` names
  * `TX_BASE_COST`, `TX_DATA_COST_PER_ZERO` and `TX_DATA_COST_PER_NON_ZERO` in
  * `frontier/vm/gas.py` and reads them in `calculate_intrinsic_cost`; the
  * creation surcharge arrives with EIP-2 as a fourth. `GasSchedule` holds all
  * four for that reason, and EIP-2028 -- which moves the non-zero-byte price and
  * nothing else -- is the delta shape this arrangement exists to express.
  */
object IntrinsicGas:

  /** The charge for a transaction carrying `data` and declaring `accessList`.
    *
    * `deploys` is the recipient being absent rather than a property of the data.
    * A transaction that deploys states no recipient, and the surcharge it pays
    * for doing so is a number the schedule holds: at the original specification
    * it is zero, which is that fork's whole answer rather than a missing case.
    *
    * The two data prices are counted over the same bytes, so a byte is charged
    * once at one price or once at the other. The zero bytes are counted and the
    * rest taken as the difference, rather than counting both, because a
    * partition counted twice can disagree with itself.
    *
    * ==THE DECLARATION IS COUNTED AS ENCODED, DUPLICATES AND ALL==
    *
    * A format carrying no such declaration passes an empty sequence, which is
    * charged nothing at prices held at zero, so the term is the same arithmetic
    * at every fork rather than a case. Where a fork does price it, the count is
    * over the sequence and never over a set: *"non-unique addresses and storage
    * keys are not disallowed, though they will be charged for multiple times"*
    * (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-2930.md`, Final).
    * `ethereum/execution-specs` @ `20f7f6271` counts the same way, looping the
    * sequence at `forks/berlin/transactions.py:285-291`, and `besu-eth/besu` @
    * `fdf1247c6` sums `entries.size()` and each entry's `storageKeys().size()`
    * without distinguishing.
    *
    * **The same declaration ALSO seeds a set**, of what the transaction may then
    * reach at the reduced price, and that one does deduplicate. So a reader
    * reaching for `.distinct` here has confused the two halves of one field, and
    * the result undercharges every transaction that repeats an entry -- a state
    * root apart, on a transaction anyone can construct.
    *
    * ==THE PER-WORD TERM IS PAID ONLY BY A TRANSACTION THAT DEPLOYS==
    *
    * EIP-3860 extends *"the transaction data cost formula to include
    * `initcode_cost(initcode)`"* **"For a create transaction"**
    * (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-3860.md`, Final, rule 2), where
    * `initcode_cost(initcode) = INITCODE_WORD_COST * ceil(len(initcode) / 32)`.
    * So it joins the surcharge for deploying rather than the data prices, over
    * the same bytes the data prices already count -- a deploying transaction
    * pays for its data twice, once per byte and once per word, and a calling
    * transaction pays the word term not at all.
    *
    * `ethereum/execution-specs` @ `20f7f6271a` writes it inside the same branch
    * as the surcharge, `create_cost = GasCosts.TX_CREATE +
    * init_code_cost(ulen(tx.data))` (`forks/shanghai/transactions.py:385`), and
    * `ethereum/go-ethereum` @ `e9e35a42f` adds it under
    * `if isContractCreation && rules.IsShanghai`
    * (`core/state_transition.go:115-119`). A term added outside the branch
    * overcharges every ordinary call, which no state fixture of a deploying
    * transaction can see.
    */
  def of(schedule: GasSchedule, data: Bytes, deploys: Boolean, accessList: Seq[AccessTuple]): BigInt =
    val raw = data.toIArray
    var zeros = 0
    var index = 0
    while index < raw.length do
      if raw(index) == 0.toByte then zeros += 1
      index += 1
    val declared =
      schedule.transactionAccessListAddress * accessList.length +
        schedule.transactionAccessListStorageKey * accessList.map(_.storageKeys.length).sum
    val creating =
      if deploys then schedule.transactionCreate + schedule.initcodePerWord * wholeWords(raw.length)
      else BigInt(0)
    schedule.transactionBase +
      schedule.transactionDataPerZeroByte * zeros +
      schedule.transactionDataPerNonZeroByte * (raw.length - zeros) +
      creating +
      declared

  /** How many whole words `length` bytes occupy, rounding a partial word up.
    *
    * `ceil(len / 32)`, which is what the document's `initcode_cost` counts. The
    * machine counts the same way over a region a create operation names, and the
    * two arrive at one figure by the same arithmetic rather than by one calling
    * the other -- they are handed different things, an operand there and a
    * transaction's data here.
    */
  private def wholeWords(length: Int): BigInt = (BigInt(length) + Word.Width - 1) / Word.Width
