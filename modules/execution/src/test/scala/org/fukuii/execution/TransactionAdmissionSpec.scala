package org.fukuii.execution

import org.scalatest.flatspec.AnyFlatSpec

import org.fukuii.bytes.{Address, Bytes, UInt256, UInt64}
import org.fukuii.crypto.Secp256k1
import org.fukuii.evm.EvmFixtures
import org.fukuii.types.{Sender, SigningPreimage, Transaction, TransactionType}

/** What admission decides about a SIGNATURE, which no published fixture at the
  * forks this build carries can reach.
  *
  * ==The chain identifier is TWO rules, and no published case reaches either==
  *
  * Whether a signature may name a chain, and whether the one it named is this
  * network's. Measured over every `txbytes` in the three generated state
  * corpora at the `tests@v20.0.1` release, by decoding each one rather than by
  * matching its text: two per corpus are typed envelopes, which these forks
  * refuse by format, and every legacy signature carries a `v` of 27 or 28 --
  * unprotected, naming no chain -- but for a single case in each of the two
  * later corpora whose `v` is 34, which names no signing scheme at all and is
  * refused as an invalid signature. **So nothing on disk names a chain**, which
  * puts both rules out of the corpus's reach at once: the comparison has no
  * identifier to read, and the rule above it no protected signature to refuse.
  *
  * **A rule the corpus cannot reach is a rule that can be inverted with every
  * signal green**, and that was confirmed by inverting it: the whole
  * certification stayed green and only the cases below reported it. They are
  * written against rule values directly rather than against a network's,
  * because neither rule belongs to any one fork.
  *
  * The bound on `s` is in a different position and is not stated as though it
  * were the same: the corpus signs canonically, so nothing published carries an
  * `s` above the bound, but a corpus read at rules that apply the bound does
  * exercise the branch that lets a low `s` through.
  *
  * ==The signatures are real, which is what makes the malleation check mean
  * something==
  *
  * Each transaction below is signed with the curve rather than assembled from
  * plausible-looking numbers, so a recovered sender is the account that signed
  * and not a well-formed accident.
  */
class TransactionAdmissionSpec extends AnyFlatSpec:

  private val recipient: Address = EvmFixtures.address(0x22)

  private val signing: BigInt = BigInt("4a2ffc8867fd8d1773481cf13f36e44f033133c579520d2745e46c3bbbf21e6a", 16)

  /** The account [[signing]] belongs to, derived rather than stated. */
  private val signer: Address =
    Sender.addressOf(Secp256k1.publicKeyOf(signing).getOrElse(fail("the signing key has no public key")))

  private def quantity(value: BigInt): UInt256 =
    UInt256.fromBigInt(value).getOrElse(fail("a fixture quantity does not fit a machine word"))

  private val unsigned: Transaction.Legacy =
    Transaction.Legacy(
      nonce = UInt64.Zero,
      gasPrice = quantity(10),
      gasLimit = UInt64.fromBits(100000),
      to = Some(recipient),
      value = UInt256.Zero,
      data = Bytes.Empty,
      v = UInt256.Zero,
      r = UInt256.Zero,
      s = UInt256.Zero
    )

  /** [[unsigned]] signed for `chainId`, or unprotected where none is given.
    *
    * The `v` is assembled the way EIP-155 states it -- `chainId * 2 + 35`, or 27
    * for the earlier scheme -- because a legacy transaction carries the
    * identifier nowhere else.
    */
  private def signedFor(chainId: Option[UInt64]): Transaction.Legacy =
    val signature = Secp256k1
      .sign(SigningPreimage.hashForSigning(unsigned, chainId), signing)
      .getOrElse(fail("the fixture transaction could not be signed"))
    val v = chainId match
      case None     => BigInt(27) + signature.recoveryId
      case Some(id) => id.toBigInt * 2 + 35 + signature.recoveryId
    unsigned.copy(v = quantity(v), r = quantity(signature.r), s = quantity(signature.s))

  /** secp256k1's order, which the curve layer exposes only halved.
    *
    * The order is prime and therefore odd, so it is twice its own half plus
    * one. That is exact rather than approximate, and the malleation case below
    * is what proves the reconstruction: a signature reflected across an `n` off
    * by anything at all recovers a different account, or none.
    */
  private val curveOrder: BigInt = Secp256k1.halfCurveOrder * 2 + 1

  /** The same signature reflected: `(r, n - s)` with the parity flipped.
    *
    * It authorizes exactly what the original authorized and hashes to something
    * else, which is the duplicate EIP-2 exists to suppress.
    */
  private def malleated(transaction: Transaction.Legacy): Transaction.Legacy =
    val flipped = transaction.v.toBigInt match
      case v if v == 27 => BigInt(28)
      case v if v == 28 => BigInt(27)
      case v            => if (v - 35) % 2 == 0 then v + 1 else v - 1
    transaction.copy(v = quantity(flipped), s = quantity(curveOrder - transaction.s.toBigInt))

  private val ethereum: UInt64 = UInt64.fromBits(1)
  private val classic: UInt64 = UInt64.fromBits(61)

  /** Rules to read a signature against, defaulting to the earliest set any
    * network in this build carries.
    *
    * The defaults are the values every rule set in the tree holds today, so a
    * case that says nothing about a rule is read at the fork that refuses the
    * most -- and a case about a later rule has to name it, which is what keeps
    * the height a case is written at visible in the case itself. Every argument
    * is named, because two of the three are booleans and transposing them would
    * compile.
    */
  private def rules(
      admittedTypes: Set[TransactionType] = Set(TransactionType.Legacy),
      signatureMayCarryChainId: Boolean = false,
      signatureSMustBeLow: Boolean = false
  ): AdmissionRules = AdmissionRules(
    admittedTypes = admittedTypes,
    signatureMayCarryChainId = signatureMayCarryChainId,
    signatureSMustBeLow = signatureSMustBeLow
  )

  /** Rules that let a signature name a chain, which is what makes comparing the
    * one it named a question at all.
    */
  private val naming: AdmissionRules = rules(signatureMayCarryChainId = true)

  // ── Whose account a signature names ───────────────────────────────────────

  "a signature" should "name the account that made it" in
    // The control. Every refusal below is satisfied by a recovery that refuses
    // everything, and this is what makes them mean what they say.
    assert(
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules()) == Right(signer),
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules()).toString
    )

  // ── The chain identifier ──────────────────────────────────────────────────
  //
  // Every case here reads [[naming]], because which chain a signature named is
  // a question only rules that let it name one ever ask.

  "a transaction signed for another network" should "be refused" in
    // Signed naming chain 61 and offered to chain 1. Admitting it splits the
    // chain: it would settle here and be rejected by every node that made the
    // comparison.
    assert(
      TransactionAdmission.senderOf(signedFor(Some(classic)), ethereum, naming) == Left(Refusal.WrongChainId),
      TransactionAdmission.senderOf(signedFor(Some(classic)), ethereum, naming).toString
    )

  it should "be accepted by the network it names" in
    // The other side of the same comparison. Without it, a comparison that
    // refused every protected signature would satisfy the case above.
    assert(
      TransactionAdmission.senderOf(signedFor(Some(classic)), classic, naming) == Right(signer),
      TransactionAdmission.senderOf(signedFor(Some(classic)), classic, naming).toString
    )

  "an unprotected signature" should "be accepted whichever network asks" in
    // It carries no identifier at all, so it is valid on every network by
    // construction and is not a case the comparison can decide. A comparison
    // reading absence as a mismatch would refuse every legacy transaction
    // signed before EIP-155.
    assert(
      TransactionAdmission.senderOf(signedFor(None), classic, naming) == Right(signer),
      TransactionAdmission.senderOf(signedFor(None), classic, naming).toString
    )

  // ── Whether a signature may name a chain at all ───────────────────────────

  "a signature naming a chain" should "be refused where these rules admit none" in
    // Signed naming chain 1 and offered to chain 1, so the comparison above has
    // nothing to refuse it by and only this rule can. Before EIP-155 the only
    // `v` a signature may carry is 27 or 28, and one naming a chain recovers a
    // well-formed address that the network it was offered to must not settle.
    assert(
      TransactionAdmission.senderOf(signedFor(Some(ethereum)), ethereum, rules()) ==
        Left(Refusal.InvalidSignature),
      TransactionAdmission.senderOf(signedFor(Some(ethereum)), ethereum, rules()).toString
    )

  "a signature naming no chain" should "be admitted where these rules admit none" in
    // The other side of the same rule, at the same rules. EIP-155 does not make
    // the earlier scheme invalid -- it adds a second one -- so a rule that
    // refused both would refuse every transaction these forks ever carried.
    assert(
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules()) == Right(signer),
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules()).toString
    )

  "a signature naming another network's chain" should "be refused by the rule it breaks first" in
    // It breaks both rules at once, and which refusal that reports is the thing
    // a corpus states. Below EIP-155 the fork has no identifier to compare
    // against, so the refusal is the signature's: a rule set reporting a chain
    // mismatch here would be answering a question it cannot yet ask.
    assert(
      TransactionAdmission.senderOf(signedFor(Some(classic)), ethereum, rules()) ==
        Left(Refusal.InvalidSignature),
      TransactionAdmission.senderOf(signedFor(Some(classic)), ethereum, rules()).toString
    )

  // ── EIP-2's bound on `s` ──────────────────────────────────────────────────

  "a reflected signature" should "name the same account as the one it reflects" in
    // What makes the bound a rule rather than a formality: one authorization
    // presents under two transaction hashes, and the curve cannot tell them
    // apart. This also proves the reflection is a real one, which every case
    // below rests on.
    assert(
      Sender.recover(malleated(signedFor(None))) == Right(signer),
      Sender.recover(malleated(signedFor(None))).toString
    )

  it should "carry an `s` above half the curve order" in
    // The reflection is only a test of the bound if it lands on the far side of
    // it. Signing canonicalizes, so the original is always below.
    assert(
      malleated(signedFor(None)).s.toBigInt > Secp256k1.halfCurveOrder,
      malleated(signedFor(None)).s.toBigInt.toString
    )

  it should "be accepted where the bound is not in force" in
    assert(
      TransactionAdmission.senderOf(malleated(signedFor(None)), ethereum, rules()) == Right(signer),
      TransactionAdmission.senderOf(malleated(signedFor(None)), ethereum, rules()).toString
    )

  it should "be refused once the bound is in force" in
    assert(
      TransactionAdmission.senderOf(
        malleated(signedFor(None)),
        ethereum,
        rules(signatureSMustBeLow = true)
      ) == Left(Refusal.InvalidSignature),
      TransactionAdmission
        .senderOf(malleated(signedFor(None)), ethereum, rules(signatureSMustBeLow = true))
        .toString
    )

  "the signature it reflects" should "still be accepted once the bound is in force" in
    // The bound refuses one of the pair and not the other, which is the whole
    // of what it does. A bound applied to both would refuse every transaction
    // ever signed.
    assert(
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules(signatureSMustBeLow = true)) == Right(signer),
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules(signatureSMustBeLow = true)).toString
    )

  // ── The format gate, asked before anything is spent on the signature ──────

  "a format this network does not carry" should "be refused before its signature is read" in
    // The transaction is validly signed for this network, so nothing but the
    // format can be refusing it.
    assert(
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules(admittedTypes = Set.empty)) ==
        Left(Refusal.TypeNotAdmitted),
      TransactionAdmission.senderOf(signedFor(None), ethereum, rules(admittedTypes = Set.empty)).toString
    )
