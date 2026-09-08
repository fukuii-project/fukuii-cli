package org.fukuii.evm

import org.fukuii.bytes.Bytes
import org.scalatest.flatspec.AnyFlatSpec

/** The numbering, checked at the boundaries that fix it.
  *
  * The cases are generated from `forks/frontier/vm/instructions/__init__.py`'s
  * `Ops` enum at `ccaaaba58`, so the risk this covers is not a mistyped digit
  * but a wrong generation: the anchors below are read from that file directly,
  * and between them the ranges are required to be contiguous, which pins every
  * member of the three families that differ only by a count.
  */
class OpcodeSpec extends AnyFlatSpec:

  "the vocabulary" should "hold every operation this build knows, across forks" in
    // 143, counted in the file rather than recalled. **This is not a per-fork
    // figure**: a byte's meaning does not change once it has one, so the enum
    // accumulates across forks and a table selects from it. The number rises
    // with the first operation each proposal adds, and is a counted fact either
    // way.
    //
    // 134 until Constantinople, which added five: EIP-145's SHL, SHR and SAR,
    // EIP-1052's EXTCODEHASH and EIP-1014's CREATE2. 139 until Istanbul, which
    // added two: EIP-1344's CHAINID and EIP-1884's SELFBALANCE. 141 until
    // London, which added one: EIP-3198's BASEFEE. 142 until Shanghai, which
    // added one: EIP-3855's PUSH0. Every one of the nine is also in
    // `OpcodeTable.laterThanOriginal`, so the FRONTIER table's own size is
    // unmoved -- the two counts answer different questions and only this one
    // rises when an operation is added.
    assert(Opcode.values.length == 143, "the Ops enum has 143 members, counted in the file rather than recalled")

  it should "give each operation a distinct byte" in
    assert(
      Opcode.values.map(_.code).distinct.length == Opcode.values.length,
      "two operations sharing a byte would make decoding ambiguous"
    )

  it should "number the halting operation zero" in
    assert(Opcode.Stop.code == 0x00, "STOP = 0x00")

  it should "number the digest operation 0x20" in
    assert(Opcode.Keccak256.code == 0x20, "KECCAK = 0x20 in the specification, KECCAK256 in go-ethereum")

  it should "number the jump marker 0x5b" in
    assert(Opcode.JumpDest.code == 0x5b, "JUMPDEST = 0x5b")

  it should "number the destruction operation 0xff" in
    assert(Opcode.SelfDestruct.code == 0xff, "SELFDESTRUCT = 0xff")

  "the push family" should "run from 0x60 to 0x7f without a gap" in
    assert(
      Opcode.values.filter(Opcode.isPush).map(_.code).toSeq.sorted == (0x60 to 0x7f).toSeq,
      "PUSH1 = 0x60 through PUSH32 = 0x7f, thirty-two contiguous operations"
    )

  it should "take one more operand byte for each step up the family" in
    assert(
      Opcode.values.filter(Opcode.isPush).toSeq.sortBy(_.code).map(Opcode.immediateWidth) == (1 to 32).toSeq,
      "the operand width is the distance from PUSH1 plus one"
    )

  it should "be the only family carrying an operand in the code" in
    assert(
      Opcode.values.filterNot(Opcode.isPush).forall(Opcode.immediateWidth(_) == 0),
      "every other operation occupies exactly its own byte"
    )

  "the operation at 0x5f" should "sit one below the push family and outside it" in
    // Both halves in one assertion because neither alone is the property: the
    // byte is contiguous with the family -- "`0x5f` means it is in a
    // 'contiguous' space with the rest of the `PUSH` implementations"
    // (`ethereum/EIPs` @ `dbfa6bee8`, `EIPS/eip-3855.md`, Final) -- and the
    // family is what `immediateWidth` reads. A build that admitted it would
    // price it a tier too high at every execution.
    assert(
      Opcode.Push0.code == 0x5f && Opcode.Push0.code == Opcode.Push1.code - 1 && !Opcode.isPush(Opcode.Push0),
      "PUSH0 = 0x5f, one below PUSH1, and not a member of the family that reads an operand"
    )

  it should "leave jump destinations exactly where they were" in
    // "jumpdest-analysis is unaffected, as `PUSH0` has no immediate data bytes"
    // (same document, Security Considerations). The code below is the case that
    // would break: a JUMPDEST immediately after it, which an operand width of
    // one would swallow as data.
    assert(
      new Code(Bytes.fromIArray(IArray(0x5f.toByte, 0x5b.toByte))).validJumpDestinations == Set(1),
      "a JUMPDEST after PUSH0 is a destination, so PUSH0 consumes no byte beyond its own"
    )

  "the duplicating and exchanging families" should "occupy 0x80 to 0x9f without a gap" in
    assert(
      Opcode.values.map(_.code).filter(code => code >= 0x80 && code <= 0x9f).toSeq.sorted == (0x80 to 0x9f).toSeq,
      "DUP1 = 0x80 through SWAP16 = 0x9f, sixteen of each"
    )

  "a byte outside the vocabulary" should "name no operation" in
    assert(
      Opcode.fromCode(0x0c).isEmpty,
      "0x0c falls in the gap the arithmetic block leaves and is defined at no fork here"
    )
