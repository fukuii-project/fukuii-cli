package org.fukuii.evm

import org.fukuii.bytes.{Address, Hash, UInt64}

/** The block an invocation is running inside, as the values it can read.
  *
  * Quantities are arbitrary precision rather than machine words. Only the
  * number is arithmetic here -- `BLOCKHASH` compares it against an operand plus
  * a window -- and that comparison has to be exact, because an operand near the
  * top of the range would wrap if the sum were taken in a 256-bit word and the
  * window would then admit a block it must refuse. The specification takes the
  * same care, widening the operand out of a word before adding to it.
  *
  * @param baseFee
  *   the charge per unit of gas a block destroys rather than pays, absent below
  *   the fork that introduced it. **Optional because the header it is read off
  *   is**: `org.fukuii.types.BlockHeader.baseFeePerGas` answers `None` for every
  *   block below that fork, and modeling one layer down as a plain quantity
  *   would mean choosing a number to stand for a field that is not there. Zero
  *   is the number that would be chosen and it is a legal base fee, so the
  *   substitution is unrecoverable rather than merely lossy.
  *
  *   **A reader is not required to handle the absence, and must not paper over
  *   it.** The one operation that reads this joins the table only at the fork
  *   that fills it, so an invocation reaching this member with nothing in it
  *   describes a rule set that put the operation in the table without the header
  *   field beside it -- a configuration this project would have had to write.
  *   That is the same obligation
  *   `org.fukuii.execution.BlockProcessor.offered` carries for the formats it
  *   cannot price, and it is discharged the same way, by refusing rather than by
  *   defaulting.
  *
  *   The field is split on how to say this and agrees on what it means:
  *   `ethereum/go-ethereum` @ `e9e35a42f8` carries a nil-able `BaseFee
  *   *big.Int`, `besu-eth/besu` @ `fdf1247c6d` an `Optional<Wei>`, and
  *   `ethereum/execution-specs` @ `20f7f6271a` gives the field only to the block
  *   environments of the forks that have it. Absence is representable in all
  *   three; which construct carries it is this build's to choose, and an option
  *   is what the layer above already uses.
  * @param prevRandao
  *   the randomness the beacon chain settled for the previous block, absent
  *   where the network runs no such beacon.
  *
  *   **Optional for a different reason than [[baseFee]] is, and the difference
  *   is worth stating because the two shapes look identical.** A base fee is
  *   absent because the header FIELD is absent below the fork that adds one.
  *   This field is not: `org.fukuii.types.Seal.MixHashAndNonce` carries a
  *   32-byte slot on every header of that shape, at every height. What is absent
  *   below the fork is the slot's MEANING as randomness -- pre-merge the same
  *   bytes are a mining artifact, and pushing them would answer plausibly and
  *   wrongly. So absence here records that the value in the header is not
  *   randomness, rather than that there is no value.
  *
  *   **It is therefore the carrier and never the decider.** What the operation
  *   at `0x44` reports is [[EvmRules.blockRandomness]]'s answer, resolved from
  *   the schedule; this member is where the value comes from once that answer is
  *   [[BlockRandomness.Eip4399]]. Deciding from the carrier instead would read
  *   post-merge-ness off data rather than off the fork, which is a second source
  *   of truth for a fact the schedule already settles.
  *
  *   `ethereum/go-ethereum` @ `e9e35a42f` (2026-08-26) splits it the same way and
  *   keeps both: `core/evm.go:63-64` fills a nil-able `random` only where the
  *   header's difficulty is zero, while `Difficulty` is set unconditionally
  *   beside it, and the fork-resolved jump table is what picks between them.
  *   `besu-eth/besu` @ `fdf1247c6d` (2026-08-26) keeps one accessor named for
  *   both readings, `getMixHashOrPrevRandao()`, and picks by which operation its
  *   fork-resolved registry holds.
  * @param excessBlobGas
  *   how much blob gas the chain has run past its target by the time this block
  *   starts, absent below the fork whose headers carry the field.
  *
  *   ==The EXCESS and not the price, which is where two production clients go
  *   the other way==
  *
  *   `ethereum/go-ethereum` @ `02872e9ef` carries a precomputed
  *   `BlobBaseFee *big.Int` on its block context and fills it in
  *   `core/evm.go:60-61` by calling `eip4844.CalcBlobFee(chain.Config(), header)`;
  *   `besu-eth/besu` @ `b330564a9` likewise has its operation read
  *   `frame.getBlobGasPrice()`. Both therefore hold a value their own fork
  *   configuration produced. **This type cannot do that without giving up the
  *   rule that decided its membership** -- its own note above states that every
  *   member is read off a header, which is what put a base fee here and kept a
  *   chain id out, and a blob charge is DERIVED from a header rather than stated
  *   by one. `org.fukuii.consensus.pos.PayloadTranslation.contextOf` is the
  *   measurable consequence: it builds one of these from a payload alone, and a
  *   precomputed charge would make it need the fork's rules as well.
  *
  *   `ethereum/execution-specs` @ `0cc100eb1` splits it the same way this does,
  *   putting `excess_blob_gas` on the block environment and deriving the charge
  *   inside the operation
  *   (`src/ethereum/forks/cancun/vm/instructions/environment.py:599-601`).
  *
  *   ==Optional for [[baseFee]]'s reason and not [[prevRandao]]'s==
  *
  *   The header field is absent below the fork that adds it, so there is no
  *   number to carry. Zero is a legal excess -- it is what every block at the
  *   fork's own start states -- so a default would answer plausibly and wrongly
  *   for a block that had no field at all.
  */
final case class BlockContext(
    coinbase: Address,
    number: BigInt,
    timestamp: BigInt,
    difficulty: BigInt,
    gasLimit: BigInt,
    baseFee: Option[BigInt],
    prevRandao: Option[Hash],
    excessBlobGas: Option[UInt64]
)

/** The transaction an invocation is running inside, as the values it can read.
  *
  * Three fields, because three operations read the transaction and no more: the
  * account that signed it, the price it pays per unit of gas, and the
  * commitments it carries. None of them changes between the invocations of one
  * transaction, which is what separates them from [[Message]].
  *
  * **The third arrived with the format that carries it**, which is why this
  * sentence counts rather than naming the operations: a later format adding a
  * value every invocation reads adds a member here, and a count is what a
  * reader checks the members against.
  *
  * @param blobVersionedHashes
  *   the commitments the transaction carries, in the order it carries them, and
  *   empty for every format that carries none.
  *
  *   **The ORDER is the value.** The operation at `0x49` reports the commitment
  *   at a stated index, so a sequence reordered anywhere reports the wrong hash
  *   at two indices while carrying the right set --
  *   `ethereum/execution-specs` @ `0cc100eb1`
  *   `src/ethereum/forks/cancun/vm/instructions/environment.py:572-573`
  *   subscripts the tuple directly, as does `ethereum/go-ethereum` @
  *   `02872e9ef` `core/vm/eips.go:275-277`.
  *
  *   **Empty and absent are the same answer HERE and not one layer up.** The
  *   operation reports zero for an index past the end, and a transaction
  *   carrying nothing is every index past the end -- so an empty sequence is
  *   the correct reading for a format with no such field. Whether a blob
  *   transaction may carry an empty one at all is a different question,
  *   decided before anything runs by
  *   `org.fukuii.execution.TransactionAdmission`.
  */
final case class TransactionContext(
    origin: Address,
    gasPrice: BigInt,
    blobVersionedHashes: Seq[Hash]
)

/** Everything outside a frame that an operation may reach.
  *
  * ==Two services and two values, held apart from the frame==
  *
  * A frame is one invocation and is discarded with it; this is shared by every
  * invocation of a transaction, so keeping the two apart is what stops an
  * invocation's own bookkeeping from being mistaken for the chain's. That split
  * is go-ethereum's: its machine holds the block context, the transaction
  * context and the state, while the per-invocation caller, address, value and
  * input ride separately. The specification nests the two contexts inside its
  * message instead; nothing here turns on which way round they sit, and this is
  * the arrangement that keeps the shared half from being rebuilt per
  * invocation.
  *
  * A class rather than a record, because two of the four members are things the
  * machine calls rather than values it reads, and comparing two environments is
  * not an operation anything needs.
  *
  * @param world
  *   the state the invocation reads and writes, named as the journal rather
  *   than as [[WorldState]] because an invocation that halts has to leave no
  *   trace and a view with no way to undo cannot run one. What varies -- a
  *   trie, a test double, a view at an earlier block -- varies underneath it.
  * @param blockHashAt
  *   the hash of an earlier block by number. It is asked only for a number the
  *   operation has already found to be inside the window the fork allows, so it
  *   is total: answering an arbitrary number is not something a caller has to
  *   arrange. go-ethereum's lookup carries the same contract.
  * @param chainId
  *   which network this is, for the one operation that reads it.
  *
  * ==Neither a fork's answer nor an invocation's, which is why it is here==
  *
  * [[EvmRules]] is what a fork decides and a chain id is invariant across every
  * fork of a network, so it does not belong there -- and putting it there would
  * cost something specific: [[EvmRules]] exists so that two networks running
  * the same rules can be asserted equal, and a chain id inside it would make
  * every network's rules differ from every other's at every height, by
  * construction. [[BlockContext]] is the other near miss: its members are all
  * read off a header, and no header carries a chain id. **That test is what put
  * the base fee there and kept the chain id here**, so it is a live rule rather
  * than a description of the members that happened to exist when it was written.
  *
  * **No surveyed client puts it on the fork-resolved rules.**
  * `ethereum/go-ethereum` @ `e9e35a42f8` holds it on `chainConfig`, a member of
  * its machine distinct from `chainRules`; `NethermindEth/nethermind` @
  * `b92e2a4719` splits the two axes by name, taking the value from
  * `specProvider.ChainId` and the fork's decision from
  * `spec.ChainIdOpcodeEnabled`. `ethereum/execution-specs` @ `20f7f6271a` puts
  * `chain_id` on the record that also holds the state and the block hashes,
  * which here is this class rather than [[BlockContext]].
  */
final class Environment(
    val world: JournaledWorldState,
    val blockHashAt: BigInt => Hash,
    val block: BlockContext,
    val transaction: TransactionContext,
    val chainId: UInt64,
    // THE CHAIN CONFIGURATION, as one value rather than as the loose operations,
    // prices and precompiles it used to be. A behavior that varies by fork is
    // what forced the bundle: a table cannot hold one and a schedule cannot
    // price one, so a fourth loose parameter was the alternative -- and three
    // had already been threaded past this type once before landing on it.
    //
    // The split is the field's. go-ethereum's `EVM` holds `chainConfig` and
    // `chainRules` beside its block and transaction contexts; besu separates the
    // `ProtocolSpec` a fork builds from the `MessageFrame` an invocation
    // carries. What a fork decides and what an invocation carries are different
    // lifetimes, and this is the seam between them.
    val rules: EvmRules
):

  /** The three the machine reads most, forwarded so an operation asks the
    * environment for what it needs rather than reaching through to the
    * configuration that produced it.
    */
  def table: OpcodeTable = rules.table

  def schedule: GasSchedule = rules.schedule

  def precompiles: PrecompileSet = rules.precompiles
