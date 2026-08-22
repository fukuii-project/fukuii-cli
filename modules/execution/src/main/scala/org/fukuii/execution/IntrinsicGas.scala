package org.fukuii.execution

import org.fukuii.bytes.Bytes
import org.fukuii.evm.GasSchedule

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

  /** The charge for a transaction carrying `data`.
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
    */
  def of(schedule: GasSchedule, data: Bytes, deploys: Boolean): BigInt =
    val raw = data.toIArray
    var zeros = 0
    var index = 0
    while index < raw.length do
      if raw(index) == 0.toByte then zeros += 1
      index += 1
    schedule.transactionBase +
      schedule.transactionDataPerZeroByte * zeros +
      schedule.transactionDataPerNonZeroByte * (raw.length - zeros) +
      (if deploys then schedule.transactionCreate else BigInt(0))
