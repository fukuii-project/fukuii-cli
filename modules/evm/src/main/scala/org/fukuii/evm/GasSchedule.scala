package org.fukuii.evm

/** What each thing the machine does costs, held as data rather than as literals
  * at the sites that spend it.
  *
  * ==Why this is a value and not a set of constants==
  *
  * Networks that run this machine disagree about prices, and they disagree
  * about them one price at a time rather than one fork at a time. A schedule
  * that is a value can be produced from whatever a chain configuration says is
  * active, handed to [[OpcodeTable]], and handed to the operations that compute
  * their own charge. A schedule inlined at its use sites can be changed only by
  * editing the machine, which is what makes a second network a fork of the
  * client instead of a configuration of it.
  *
  * ==The tiers are the specification's own structure==
  *
  * Most operations do not carry an individual price: they name one of five
  * tiers, and a repricing that moves a tier moves every operation on it. The
  * tiers are kept as tiers here for that reason, rather than being flattened
  * into a price per operation.
  *
  * ==One departure from the specification's field list==
  *
  * The specification names a separate constant for the settled part of each
  * memory and copying operation's price, and then defines every one of them as
  * the very low tier. Those are not repeated here: an operation that names the
  * tier is priced from the tier. The cost of the departure is that a repricing
  * moving one of them away from the tier cannot be expressed until the field it
  * needs exists, which is an addition to this record rather than a change to
  * anything that reads it.
  *
  * Reversing trigger: the first proposal that prices one of those operations
  * apart from the tier it currently shares.
  *
  * ==A refund is a price too, and is repriced the same way==
  *
  * [[refundStorageClear]] and [[refundSelfDestruct]] are not charges, but each
  * is a number a proposal changes and each is settled against the same
  * transaction the charges are, so they belong with them rather than as
  * constants at the sites that earn them. The specification groups them the
  * same way, under their own heading in the same table of costs.
  *
  * ==A message call's price is four numbers, not one==
  *
  * [[callBase]] is what the operation costs before anything about its
  * destination is known; [[newAccount]] is added where this state has never
  * held the account being called; [[callValue]] is added where anything is sent;
  * and [[callStipend]] is given back to the callee out of that last charge, so
  * that an account paid something always has enough gas to notice. They are
  * separate fields because proposals have moved them separately.
  *
  * ==Memory expansion is deliberately absent==
  *
  * [[GasCost]] owns the cost of holding memory, and its two parameters live
  * there with the function they parameterize. Repeating them here would give
  * one price two homes, which is how the two come to disagree.
  *
  * ==A precompile's price is a price, and belongs here rather than with it==
  *
  * Both sources keep them beside every other charge -- the specification under
  * its own heading in the same table of costs, go-ethereum in the same file of
  * protocol parameters -- and a network reprices one the way it reprices
  * anything else. [[PrecompileSet.baseline]] reads them from here into the
  * entries it builds, exactly as [[OpcodeTable.baseline]] does.
  */
final case class GasSchedule(
    base: BigInt,
    veryLow: BigInt,
    low: BigInt,
    mid: BigInt,
    high: BigInt,
    zero: BigInt,
    jumpDest: BigInt,
    blockHash: BigInt,
    balance: BigInt,
    externalBase: BigInt,
    storageLoad: BigInt,
    storageSet: BigInt,
    storageReset: BigInt,
    refundStorageClear: BigInt,
    refundSelfDestruct: BigInt,
    callBase: BigInt,
    callValue: BigInt,
    callStipend: BigInt,
    newAccount: BigInt,
    createBase: BigInt,
    codeDepositPerByte: BigInt,
    expBase: BigInt,
    expPerByte: BigInt,
    keccak256Base: BigInt,
    keccak256PerWord: BigInt,
    copyPerWord: BigInt,
    logBase: BigInt,
    logDataPerByte: BigInt,
    logTopic: BigInt,
    precompileEcRecover: BigInt,
    precompileSha256Base: BigInt,
    precompileSha256PerWord: BigInt,
    precompileRipemd160Base: BigInt,
    precompileRipemd160PerWord: BigInt,
    precompileIdentityBase: BigInt,
    precompileIdentityPerWord: BigInt,
    // THE TRANSACTION-INTRINSIC PRICES. They belong here and not beside the
    // caller that charges them, because both authorities keep them in the same
    // repriceable record as everything above -- and because EIP-2028 is exactly
    // a repricing-in-place of `transactionDataPerNonZeroByte`, which is the
    // commonest delta kind this seam exists to express. Holding them elsewhere
    // would have made the seam complete for opcodes and precompiles and absent
    // for the charge every transaction pays first.
    transactionBase: BigInt,
    transactionDataPerZeroByte: BigInt,
    transactionDataPerNonZeroByte: BigInt,
    // SELFDESTRUCT costs nothing to execute at this fork and is priced as a
    // field rather than a literal for the same reason: EIP-150 reprices it to
    // 5000, so a schedule that cannot name it cannot express that fork either.
    selfDestruct: BigInt
)

object GasSchedule:

  /** The prices the machine started with, and the floor every later schedule is
    * a change to.
    *
    * Named for what it is rather than for the fork that shipped it: this is the
    * base every network's schedule departs from, and the fork's own name belongs
    * to the family that had it, not to a value both families share.
    */
  val Baseline: GasSchedule = GasSchedule(
    base = BigInt(2),
    veryLow = BigInt(3),
    low = BigInt(5),
    mid = BigInt(8),
    high = BigInt(10),
    zero = BigInt(0),
    jumpDest = BigInt(1),
    blockHash = BigInt(20),
    balance = BigInt(20),
    externalBase = BigInt(20),
    storageLoad = BigInt(50),
    storageSet = BigInt(20000),
    storageReset = BigInt(5000),
    refundStorageClear = BigInt(15000),
    refundSelfDestruct = BigInt(24000),
    callBase = BigInt(40),
    callValue = BigInt(9000),
    callStipend = BigInt(2300),
    newAccount = BigInt(25000),
    createBase = BigInt(32000),
    codeDepositPerByte = BigInt(200),
    expBase = BigInt(10),
    expPerByte = BigInt(10),
    keccak256Base = BigInt(30),
    keccak256PerWord = BigInt(6),
    copyPerWord = BigInt(3),
    logBase = BigInt(375),
    logDataPerByte = BigInt(8),
    logTopic = BigInt(375),
    precompileEcRecover = BigInt(3000),
    precompileSha256Base = BigInt(60),
    precompileSha256PerWord = BigInt(12),
    precompileRipemd160Base = BigInt(600),
    precompileRipemd160PerWord = BigInt(120),
    precompileIdentityBase = BigInt(15),
    precompileIdentityPerWord = BigInt(3),
    transactionBase = BigInt(21000),
    transactionDataPerZeroByte = BigInt(4),
    transactionDataPerNonZeroByte = BigInt(68),
    selfDestruct = BigInt(0)
  )
