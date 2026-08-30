package org.fukuii.chainspec.proposals.eip

import org.fukuii.bytes.{Address, Bytes}
import org.fukuii.chainspec.UpgradeRules
import org.fukuii.chainspec.networks.ethereum
import org.fukuii.evm.EvmFixtures.MapWorldState
import org.fukuii.evm.{Code, EvmFixtures, Frame, Interpreter, Word}
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** EIP-1283's own seventeen published cases, run at this network's real prices.
  *
  * ==Here rather than with the machine, and this is the same exception
  * `Eip140Spec` documents==
  *
  * The machine's specs run against a schedule whose prices are deliberately no
  * network's, so none of them can produce the figures the document publishes.
  * This can, because the rules it runs are the ones this network resolves once
  * it adopts the document.
  *
  * ==Why seventeen and not one==
  *
  * *"Below we provide 17 test cases. 15 of them covering consecutive two
  * `SSTORE` operations are based on work by @chfast. Two additional cases with
  * three `SSTORE` operations is used to test the case when a slot is reset and
  * then set again"* (`ethereum/EIPs` @ `dbfa6bee`, `EIPS/eip-1283.md`, Final).
  *
  * The scheme has nine clauses over three values, and a table of two-store
  * programs is what reaches the combinations a single case cannot: the same
  * slot written twice in one transaction is the only way the *dirty* branch is
  * reachable at all, and the two three-store rows are the only way the
  * reset-after-reset clause is.
  *
  * **Each row states the used gas AND the refund**, which matters because the
  * two are separable failures -- a scheme can charge correctly and account
  * refunds wrongly, and the dirty branch is where that is most likely, since it
  * is the one that both adds and subtracts.
  */
class Eip1283PropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  private val netMetered: UpgradeRules = ethereum.Upgrades.byzantium.adopting(Eip1283.component)

  /** The account `EvmFixtures.message` runs as, which is the one these programs
    * store under.
    */
  private val storesUnder: Address = EvmFixtures.address(0x22)

  private val budget: BigInt = BigInt(1000000)

  final private case class Case(code: String, usedGas: Int, refund: Int, original: Int)

  /** The document's table, transcribed. The 1st/2nd/3rd columns are what the
    * code writes and are not restated here -- they are already in the code.
    */
  private val published: Seq[Case] = Seq(
    Case("60006000556000600055", 412, 0, 0),
    Case("60006000556001600055", 20212, 0, 0),
    Case("60016000556000600055", 20212, 19800, 0),
    Case("60016000556002600055", 20212, 0, 0),
    Case("60016000556001600055", 20212, 0, 0),
    Case("60006000556000600055", 5212, 15000, 1),
    Case("60006000556001600055", 5212, 4800, 1),
    Case("60006000556002600055", 5212, 0, 1),
    Case("60026000556000600055", 5212, 15000, 1),
    Case("60026000556003600055", 5212, 0, 1),
    Case("60026000556001600055", 5212, 4800, 1),
    Case("60026000556002600055", 5212, 0, 1),
    Case("60016000556000600055", 5212, 15000, 1),
    Case("60016000556002600055", 5212, 0, 1),
    Case("60016000556001600055", 412, 0, 1),
    Case("600160005560006000556001600055", 40218, 19800, 0),
    Case("600060005560016000556000600055", 10218, 19800, 1)
  )

  private def run(program: String, original: Int): Frame =
    val world = new MapWorldState
    if original != 0 then world.slots((storesUnder, Word.Zero)) = Word(BigInt(original))
    val environment = EvmFixtures.environmentUnder(netMetered.evm, world)
    val frame = new Frame(
      EvmFixtures.message(transfersValue = true),
      Code(Bytes.fromArray(program.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray)),
      budget
    )
    val _ = Interpreter.run(frame, environment)
    frame

  property("every published case is charged the gas the document states") {
    forAll(Table("case", published*)) { (published: Case) =>
      val frame = run(published.code, published.original)
      val used = budget - frame.gasLeft
      assert(
        used == BigInt(published.usedGas),
        s"code ${published.code} at original ${published.original.toString}: " +
          s"used ${used.toString} where the document states ${published.usedGas.toString}"
      )
    }
  }

  property("every published case earns the refund the document states") {
    forAll(Table("case", published*)) { (published: Case) =>
      val frame = run(published.code, published.original)
      assert(
        frame.refundCounter == BigInt(published.refund),
        s"code ${published.code} at original ${published.original.toString}: " +
          s"refund ${frame.refundCounter.toString} where the document states ${published.refund.toString}"
      )
    }
  }

  property("the legacy scheme disagrees with the net one on the published set") {
    // THE CONTROL. Without it every assertion above would pass on a build that
    // ignored the switch entirely and priced everything the old way -- which is
    // precisely the failure mode of adopting a proposal and not wiring it.
    // Measured rather than assumed: at least one row must answer differently.
    val legacy = ethereum.Upgrades.byzantium
    val differing = published.count { one =>
      val world = new MapWorldState
      if one.original != 0 then world.slots((storesUnder, Word.Zero)) = Word(BigInt(one.original))
      val environment = EvmFixtures.environmentUnder(legacy.evm, world)
      val frame = new Frame(
        EvmFixtures.message(transfersValue = true),
        Code(Bytes.fromArray(one.code.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray)),
        budget
      )
      val _ = Interpreter.run(frame, environment)
      (budget - frame.gasLeft) != BigInt(one.usedGas)
    }
    assert(differing > 0, "the two schemes agree on every published case, so this table cannot tell them apart")
  }
