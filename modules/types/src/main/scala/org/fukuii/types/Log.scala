package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.rlp.{RlpCodec, RlpError, RlpItem}

/** A record a contract emits during execution: the Yellow Paper's `O`, a tuple
  * of the logger's address, a possibly empty series of 32-byte topics, and some
  * number of bytes of data.
  *
  * ==The topic count is not bounded here, and that is deliberate==
  *
  * Only five opcodes emit a log and the widest carries four topics, so nothing
  * a contract can execute produces a fifth. **That is a bound on production,
  * never on decoding.** The Yellow Paper's type is a series with no stated
  * length, the executable specification's is an unbounded tuple, and neither
  * reference client caps it. A decoder that rejected five would refuse a
  * well-formed entry on a rule no specification states.
  *
  * ==A topic is a 32-byte word, which is why it is a [[org.fukuii.bytes.Hash]]==
  *
  * The Yellow Paper requires only that each topic lie in the set of 32-byte
  * sequences; the value is often a hash of an event signature, and just as
  * often an indexed argument padded to the width. So `Hash` is being used here
  * for its width rather than for its provenance, which is a genuine looseness:
  * one reference client models a distinct topic type over the same 32 bytes.
  * The width is what the encoding constrains, and a second value type over it
  * would buy a distinction nothing at this layer consumes.
  *
  * @param data
  *   arbitrary and not indexable — it is the one field the bloom filter does
  *   not read, so two logs differing only here share a bloom.
  */
final case class Log(
    address: Address,
    topics: Seq[Hash],
    data: Bytes
)

object Log:

  /** The number of fields, fixed. A log has encoded as three elements at every
    * fork, so this is a constant rather than something a decoder reads.
    */
  val FieldCount: Int = 3

  /** The widest log-emitting opcode's topic count.
    *
    * Stated because it is the number a reader expects the decoder to enforce,
    * and it does not: see the type's own documentation. Nothing here compares
    * against it — it exists so that the absence of a check is visibly a
    * decision.
    */
  val MaxTopicsFromOpcode: Int = 4

  /** `[address, [topic, ...], data]`, in that order.
    *
    * The topics are a nested list rather than flattened siblings, so a log with
    * no topics carries an empty list and not an absent element — the arity is
    * three whatever the topic count.
    */
  given logCodec: RlpCodec[Log] with

    def encode(value: Log): RlpItem =
      RlpItem.Sequence(
        Vector(
          RlpCodec[Address].encode(value.address),
          RlpCodec[Seq[Hash]].encode(value.topics),
          RlpCodec[Bytes].encode(value.data)
        )
      )

    def decode(item: RlpItem): Either[RlpError, Log] = item match
      case RlpItem.Sequence(items) =>
        if items.length != FieldCount then Left(RlpError.WrongArity(FieldCount, items.length))
        else
          for
            address <- RlpCodec[Address].decode(items(0))
            topics <- RlpCodec[Seq[Hash]].decode(items(1))
            data <- RlpCodec[Bytes].decode(items(2))
          yield Log(address, topics, data)
      case _: RlpItem.Bytes => Left(RlpError.ExpectedSequence)
