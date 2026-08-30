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
  * destination is known; [[newAccount]] is added where the call brings the
  * destination into being, under a condition
  * [[org.fukuii.evm.EvmRules.newAccountCharge]] rather than this schedule
  * settles; [[callValue]] is added where anything is sent; and [[callStipend]]
  * is given back to the callee out of that last charge, so that an account paid
  * something always has enough gas to notice. They are separate fields because
  * proposals have moved them separately.
  *
  * ==Memory expansion is deliberately absent==
  *
  * [[GasCost]] owns the cost of holding memory, and its two parameters live
  * there with the function they parameterize. Repeating them here would give
  * one price two homes, which is how the two come to disagree.
  *
  * ==WHERE a price is read decides whether moving it here is enough==
  *
  * A price is consumed in one of two places, and a proposal that edits only this
  * record is right for one of them and silently wrong for the other.
  * [[OpcodeTable.original]] and the composition that builds a chain's precompile
  * set both COPY a price out of this record when they build their entries, so an
  * operation charged through an entry is charged what the record held at build
  * time and a later edit never reaches it. An operation that reads the schedule
  * at the moment it spends is charged what the record holds now.
  *
  * Three classes follow, and the two that are not the obvious one both fail
  * quietly:
  *
  *   - read only at spend time -- editing this record is the whole change;
  *   - read at BOTH -- editing this record alone HALF-APPLIES, moving the
  *     spend-time sites and leaving every table entry at its old price;
  *   - read only at build time -- editing this record alone is a COMPLETE
  *     SILENT NO-OP.
  *
  * **Which field is in which class is deliberately not listed here.** It is a
  * property of where the machine happens to read each price today and it moves
  * whenever an operation is rewritten, so a list would rot without anyone
  * touching this file.
  *
  * Re-derive it, and **match on the FIELD NAME rather than on `schedule.f`.**
  * The qualifier is not always `schedule`, so a pattern anchored to it
  * under-reaches without failing: each network's `Upgrades.scala` reads these
  * through `genesisPrices.f` when it builds its precompile set, so `schedule.f`
  * finds no build-time home for any precompile price and reports every one of
  * them as read nowhere at all.
  *
  * The build-time homes are [[OpcodeTable.original]], the precompile
  * constructions in each network's `Upgrades.scala`, and every proposal that
  * bakes an entry or a precompile of its own rather than going through the
  * table -- EIP-150 and EIP-211 for table entries, EIP-196, EIP-197 and EIP-198
  * for precompile prices. The spend-time homes are `Interpreter.scala` and, for
  * the intrinsic prices, `IntrinsicGas.scala` in the layer that settles a
  * transaction around an invocation.
  *
  * **`PrecompileSet.scala` is not a home and never was.** It reads no field of
  * this record. A recipe naming it -- as this one did, until an attempt to
  * follow it measured the file and found nothing -- sends the reader to an
  * empty file and returns a zero that reads as an answer.
  *
  * **Calibrate before believing any of it** -- [[externalBase]] is read in both
  * and [[callBase]] only at spend time, so an instrument reporting those two
  * alike is measuring something other than what it claims.
  *
  * A proposal in the second or third class has to reach the table or the
  * precompile set as well, which is what [[OpcodeTable.adding]] exists for.
  * EIP-150's state-read repricing is the worked instance, and it covers the
  * table only -- no proposal has yet had to reach a precompile price. The
  * proposals themselves are a chain configuration's and are not in this module.
  *
  * ==A precompile's price is a price, and belongs here rather than with it==
  *
  * Both sources keep them beside every other charge -- the specification under
  * its own heading in the same table of costs, go-ethereum in the same file of
  * protocol parameters -- and a network reprices one the way it reprices
  * anything else. A chain configuration reads them from here into the precompile
  * entries it builds, exactly as [[OpcodeTable.original]] does for operations.
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
    extCodeHash: BigInt,
    storageLoad: BigInt,
    storageSet: BigInt,
    storageReset: BigInt,
    refundStorageClear: BigInt,
    netStorageNoop: BigInt,
    netStorageInit: BigInt,
    netStorageClean: BigInt,
    netStorageDirty: BigInt,
    refundNetStorageClear: BigInt,
    refundNetStorageResetFromZero: BigInt,
    refundNetStorageReset: BigInt,
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
    // The divisor in modular exponentiation's price. It is the one precompile
    // figure here that is not itself a charge: that precompile's charge is
    // worked out from the widths its input declares and then divided by this.
    // It is a member for the same reason its siblings are, EIP-2565 moving it
    // from 20 to 3. A network states it from its own genesis like every other
    // price here, where it prices nothing until a proposal places the native
    // that reads it -- which is what leaves that proposal a placement rather
    // than a placement and a repricing at once, the shape `transactionCreate`
    // above is held at zero for.
    precompileModExpDivisor: BigInt,
    precompileAltBn128Add: BigInt,
    precompileAltBn128Mul: BigInt,
    // What a pairing check costs before any pair is read, and what each pair
    // adds. A "point" is the ecosystem's word for one PAIR here -- a point from
    // each group, 192 bytes -- because that is what the proposal's own formula
    // counts.
    precompileAltBn128PairingBase: BigInt,
    precompileAltBn128PairingPerPoint: BigInt,
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
    // A surcharge a transaction pays for deploying rather than calling. The
    // original specification charges nothing, which is the whole of what it
    // does at that fork: it names no such constant and its intrinsic cost is a
    // base plus the data. EIP-2 introduces one, and holding it here at zero
    // rather than adding the field with the proposal is what makes that delta a
    // repricing in place -- the shape the seam expresses best -- instead of a
    // change to the record's own shape.
    transactionCreate: BigInt,
    // SELFDESTRUCT costs nothing to execute at this fork and is priced as a
    // field rather than a literal for the same reason: EIP-150 reprices it to
    // 5000, so a schedule that cannot name it cannot express that fork either.
    selfDestruct: BigInt,
    // The surcharge SELFDESTRUCT pays where the account it pays out to has never
    // existed. It is deliberately NOT `newAccount`, which the call family pays
    // for the same situation and at the same figure: the specification, the
    // largest production client and the largest production JVM client all name
    // the two separately, and they diverge at a later fork where one becomes a
    // quantity computed from the bytes an account occupies and the other ceases
    // to exist at all.
    selfDestructNewAccount: BigInt
)
