package org.fukuii.execution

import org.fukuii.bytes.{Address, Bytes, Hash, UInt64}
import org.fukuii.evm.{BlockContext, EvmFixtures, Word}
import org.scalatest.flatspec.AnyFlatSpec

/** A system call whose outcome is CHECKED, against one whose outcome is not.
  *
  * ==The two kinds are the subject, not the one call==
  *
  * Every system call this build had before this phase ran unchecked: its result
  * was discarded and nothing it did could refuse a block. A checked call refuses
  * on two separately decidable conditions, and the pair below is what separates
  * them -- an unchecked call meeting either condition still leaves the block
  * valid, and a build with one code path for both would fail exactly these.
  *
  * ==The two conditions are apart because the networks decide them apart==
  *
  * Prague refuses on both; Gnosis's withdrawal call refuses on a failed call and
  * tolerates an undeployed target. A single "checked" answer could not express
  * the second, and the published corpus names them separately as well.
  */
class CheckedSystemCallSpec extends AnyFlatSpec:

  private val target: Address = SystemCall.Target.BeaconRoots.address

  private val chainId: UInt64 = UInt64.fromBits(1)

  /** Code that stops immediately, returning nothing. */
  private val stops: Bytes = EvmFixtures.bytesOf("0x00")

  /** Code that reverts over an empty region, which is a call that ran and did
    * not end normally.
    */
  private val reverts: Bytes = EvmFixtures.bytesOf("0x60006000fd")

  /** Code that returns a single word, so return data is observable. */
  private val returnsAWord: Bytes = EvmFixtures.bytesOf("0x602a60005260206000f3")

  private def block: BlockContext =
    BlockContext(
      coinbase = Address.fromBytesTruncating(IArray.empty),
      number = BigInt(1),
      timestamp = BigInt(1000),
      difficulty = BigInt(0),
      gasLimit = BigInt(30000000),
      baseFee = Some(BigInt(7)),
      prevRandao = Some(Hash.fromBytesTruncating(IArray.empty)),
      excessBlobGas = Some(UInt64.Zero)
    )

  private def worldWith(code: Option[Bytes]): EvmFixtures.MapWorldState =
    val built = new EvmFixtures.MapWorldState
    code.foreach(built.setCode(target, _))
    built

  private def checked(code: Option[Bytes]) =
    SystemCall.runChecked(
      SystemCall(SystemCall.Target.BeaconRoots, Bytes.Empty),
      worldWith(code),
      block,
      _ => throw new AssertionError("no case here asks for a block hash"),
      chainId,
      EvmFixtures.rules
    )

  private def unchecked(code: Option[Bytes]) =
    SystemCall.run(
      SystemCall(SystemCall.Target.BeaconRoots, Bytes.Empty),
      worldWith(code),
      block,
      _ => throw new AssertionError("no case here asks for a block hash"),
      chainId,
      EvmFixtures.rules
    )

  "a checked call whose target holds no code" should "refuse under that condition" in
    assert(
      checked(None) == Left(SystemCallFault.TargetHoldsNoCode),
      "an undeployed target is one of the two conditions, and is named rather than folded"
    )

  "an unchecked call whose target holds no code" should "leave the block valid" in
    // The same world, the other kind. This is what makes the case above about
    // CHECKEDNESS rather than about the world being empty.
    assert(
      unchecked(None).isEmpty,
      "an unchecked call over an undeployed target is a silent no-op"
    )

  "a checked call that reverts" should "refuse under the other condition" in
    assert(
      checked(Some(reverts)) == Left(SystemCallFault.CallFailed),
      "a call that ran and did not end normally is the second condition, and it is not the first"
    )

  it should "be distinguishable from an empty target" in
    // The two conditions must not collapse. Gnosis tolerates the first and
    // refuses on the second, so a build answering one fault for both cannot
    // express a network this project already names.
    assert(
      checked(Some(reverts)) != checked(None),
      "two separately decidable conditions, reported separately"
    )

  "an unchecked call that reverts" should "leave the block valid" in
    assert(
      unchecked(Some(reverts)).isEmpty,
      "the outcome is discarded, which is the whole difference between the kinds"
    )

  "a checked call that stops" should "succeed with no return data" in
    assert(
      checked(Some(stops)) == Right(Right(Bytes.Empty)),
      "a call can succeed and return nothing, which is different from failing"
    )

  it should "be distinguishable from a call that failed" in
    // The distinction that decides a header commitment: empty return data
    // contributes no record, while a failure refuses the block. A build
    // collapsing them would either refuse valid blocks or accept invalid ones.
    assert(
      checked(Some(stops)).isRight && checked(Some(reverts)).isLeft,
      "returning nothing is success; failing is not"
    )

  "a checked call that returns" should "answer the bytes it returned" in
    // The reason this kind reports return data at all: the bytes feed a header
    // commitment, so they are the point rather than a side effect.
    assert(
      checked(Some(returnsAWord)).map(_.map(_.length)) == Right(Right(Word.Width)),
      "a word came back, and the caller needs it rather than a success flag"
    )
