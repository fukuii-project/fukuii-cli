package org.fukuii.types

/** The tag EIP-2718 puts in front of a typed payload.
  *
  * The name is that proposal's own: it defines a transaction as
  * `TransactionType || TransactionPayload` and says the tag "identifies the
  * format of the transaction". So this is the ecosystem's word for the concept
  * rather than a name chosen here.
  *
  * ==Why an enumeration rather than a number==
  *
  * The tag is bounded — "a positive unsigned 8-bit number between 0 and 0x7f" —
  * and within that bound only the values a proposal has assigned mean anything.
  * Carried as an integer, a receipt or a transaction could hold a tag no
  * decoder will ever accept, and an admission rule above this layer would be a
  * predicate over integers where it wants a predicate over a set of formats.
  *
  * The set is closed and grows by adding a case here, which is the same trade
  * [[Transaction]] documents: a registry would put the set beyond the
  * compiler's reach, and a match over it would stop being exhaustive.
  *
  * ==A receipt carries one of these too==
  *
  * EIP-2718's envelope covers both — `TransactionType || ReceiptPayload` is a
  * receipt exactly as `TransactionType || TransactionPayload` is a
  * transaction — and the tag means the same thing on each. That shared meaning
  * is why the type is here rather than inside either of them: two copies of
  * these five numbers is two places for one of them to be wrong.
  *
  * @param number
  *   the value the format's own proposal assigns, read from that proposal at
  *   the point each was written: EIP-2930 states `TransactionType` 1, EIP-1559
  *   states 2, EIP-4844's parameter table gives `BLOB_TX_TYPE` as
  *   `Bytes1(0x03)`, and EIP-7702's gives `SET_CODE_TX_TYPE` as `0x04`.
  */
enum TransactionType(val number: Int):

  /** The shape that predates the envelope and carries no tag at all.
    *
    * Zero is not a number any proposal assigns it. It is the ecosystem's name
    * for the untyped form, it is what the conformance fixtures report, and it
    * is deliberately NOT accepted as a leading byte: a transaction or receipt
    * beginning `0x00` is malformed rather than legacy, which is what both
    * reference clients do.
    */
  case Legacy extends TransactionType(0x00)

  case AccessList extends TransactionType(0x01)
  case DynamicFee extends TransactionType(0x02)
  case Blob extends TransactionType(0x03)
  case SetCode extends TransactionType(0x04)

object TransactionType:

  /** The largest value EIP-2718 admits as a type byte. Anything above it is not
    * an unknown type — it is not a type at all.
    *
    * Here rather than on a type that carries one of these, for the same reason
    * the tag itself is: the envelope covers a transaction and a receipt alike,
    * so a bound living on either would be a second place for one number to be
    * wrong, and the other would reach across for it.
    */
  val MaxTypeNumber: Int = 0x7f

  /** The format a tag names, or nothing.
    *
    * Nothing covers two cases a caller must keep apart, so neither is resolved
    * here: a value inside EIP-2718's range that no proposal has assigned yet,
    * and a value outside it that is not a tag at all. The envelope decoders
    * separate them, because only they know whether a leading byte was read as a
    * tag or as the start of a list.
    */
  def fromNumber(number: Int): Option[TransactionType] =
    values.find(_.number == number)
