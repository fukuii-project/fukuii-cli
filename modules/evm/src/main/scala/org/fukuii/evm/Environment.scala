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
  */
final case class BlockContext(
    coinbase: Address,
    number: BigInt,
    timestamp: BigInt,
    difficulty: BigInt,
    gasLimit: BigInt
)

/** The transaction an invocation is running inside, as the values it can read.
  *
  * Two fields, because two of this fork's operations read the transaction and
  * no more: the account that signed it, and the price it pays per unit of gas.
  * Neither changes between the invocations of one transaction, which is what
  * separates them from [[Message]].
  */
final case class TransactionContext(origin: Address, gasPrice: BigInt)

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
  * construction. [[BlockContext]] is the other near miss: its five members are
  * all read off a header, and no header carries a chain id.
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
