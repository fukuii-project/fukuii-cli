package org.fukuii.evm

/** The operations the machine knows, and the byte each is written as.
  *
  * ==This is vocabulary, not membership==
  *
  * Being a member here says an operation EXISTS, never that it is active. Which
  * operations a chain actually runs is [[OpcodeTable]]'s answer, because that
  * varies per network and per fork while the numbering does not: a byte means
  * the same operation everywhere it is defined at all. Keeping the two apart is
  * what lets a table subtract an operation without deleting the vocabulary that
  * names it.
  *
  * ==Naming, where two authorities differ==
  *
  * Every number below is generated from the executable specification's `Ops`
  * enum rather than transcribed, so a digit cannot have been mistyped. The
  * mnemonics are the ecosystem's, and one is not unanimous: `0x20` is `KECCAK`
  * in the specification and `KECCAK256` in go-ethereum. The explicit form is
  * taken here because it names the digest exactly and the abbreviation reads as
  * a family rather than a width. `0xff` is `SELFDESTRUCT` in both, and the
  * older `SUICIDE` spelling is used by neither.
  */
enum Opcode(val code: Int):

  /** The specification's own grouping is by concern and this ordering is by
    * number, which is the order a decoder meets them in.
    */
  case Stop extends Opcode(0x00)
  case Add extends Opcode(0x01)
  case Mul extends Opcode(0x02)
  case Sub extends Opcode(0x03)
  case Div extends Opcode(0x04)
  case SDiv extends Opcode(0x05)
  case Mod extends Opcode(0x06)
  case SMod extends Opcode(0x07)
  case AddMod extends Opcode(0x08)
  case MulMod extends Opcode(0x09)
  case Exp extends Opcode(0x0a)
  case SignExtend extends Opcode(0x0b)
  case Lt extends Opcode(0x10)
  case Gt extends Opcode(0x11)
  case SLt extends Opcode(0x12)
  case SGt extends Opcode(0x13)
  case Eq extends Opcode(0x14)
  case IsZero extends Opcode(0x15)
  case And extends Opcode(0x16)
  case Or extends Opcode(0x17)
  case Xor extends Opcode(0x18)
  case Not extends Opcode(0x19)
  case Byte extends Opcode(0x1a)
  case Keccak256 extends Opcode(0x20)
  case Address extends Opcode(0x30)
  case Balance extends Opcode(0x31)
  case Origin extends Opcode(0x32)
  case Caller extends Opcode(0x33)
  case CallValue extends Opcode(0x34)
  case CallDataLoad extends Opcode(0x35)
  case CallDataSize extends Opcode(0x36)
  case CallDataCopy extends Opcode(0x37)
  case CodeSize extends Opcode(0x38)
  case CodeCopy extends Opcode(0x39)
  case GasPrice extends Opcode(0x3a)
  case ExtCodeSize extends Opcode(0x3b)
  case ExtCodeCopy extends Opcode(0x3c)
  case ReturnDataSize extends Opcode(0x3d)
  case ReturnDataCopy extends Opcode(0x3e)
  case BlockHash extends Opcode(0x40)
  case Coinbase extends Opcode(0x41)
  case Timestamp extends Opcode(0x42)
  case Number extends Opcode(0x43)
  case Difficulty extends Opcode(0x44)
  case GasLimit extends Opcode(0x45)
  case Pop extends Opcode(0x50)
  case MLoad extends Opcode(0x51)
  case MStore extends Opcode(0x52)
  case MStore8 extends Opcode(0x53)
  case SLoad extends Opcode(0x54)
  case SStore extends Opcode(0x55)
  case Jump extends Opcode(0x56)
  case JumpI extends Opcode(0x57)
  case Pc extends Opcode(0x58)
  case MSize extends Opcode(0x59)
  case Gas extends Opcode(0x5a)
  case JumpDest extends Opcode(0x5b)
  case Push1 extends Opcode(0x60)
  case Push2 extends Opcode(0x61)
  case Push3 extends Opcode(0x62)
  case Push4 extends Opcode(0x63)
  case Push5 extends Opcode(0x64)
  case Push6 extends Opcode(0x65)
  case Push7 extends Opcode(0x66)
  case Push8 extends Opcode(0x67)
  case Push9 extends Opcode(0x68)
  case Push10 extends Opcode(0x69)
  case Push11 extends Opcode(0x6a)
  case Push12 extends Opcode(0x6b)
  case Push13 extends Opcode(0x6c)
  case Push14 extends Opcode(0x6d)
  case Push15 extends Opcode(0x6e)
  case Push16 extends Opcode(0x6f)
  case Push17 extends Opcode(0x70)
  case Push18 extends Opcode(0x71)
  case Push19 extends Opcode(0x72)
  case Push20 extends Opcode(0x73)
  case Push21 extends Opcode(0x74)
  case Push22 extends Opcode(0x75)
  case Push23 extends Opcode(0x76)
  case Push24 extends Opcode(0x77)
  case Push25 extends Opcode(0x78)
  case Push26 extends Opcode(0x79)
  case Push27 extends Opcode(0x7a)
  case Push28 extends Opcode(0x7b)
  case Push29 extends Opcode(0x7c)
  case Push30 extends Opcode(0x7d)
  case Push31 extends Opcode(0x7e)
  case Push32 extends Opcode(0x7f)
  case Dup1 extends Opcode(0x80)
  case Dup2 extends Opcode(0x81)
  case Dup3 extends Opcode(0x82)
  case Dup4 extends Opcode(0x83)
  case Dup5 extends Opcode(0x84)
  case Dup6 extends Opcode(0x85)
  case Dup7 extends Opcode(0x86)
  case Dup8 extends Opcode(0x87)
  case Dup9 extends Opcode(0x88)
  case Dup10 extends Opcode(0x89)
  case Dup11 extends Opcode(0x8a)
  case Dup12 extends Opcode(0x8b)
  case Dup13 extends Opcode(0x8c)
  case Dup14 extends Opcode(0x8d)
  case Dup15 extends Opcode(0x8e)
  case Dup16 extends Opcode(0x8f)
  case Swap1 extends Opcode(0x90)
  case Swap2 extends Opcode(0x91)
  case Swap3 extends Opcode(0x92)
  case Swap4 extends Opcode(0x93)
  case Swap5 extends Opcode(0x94)
  case Swap6 extends Opcode(0x95)
  case Swap7 extends Opcode(0x96)
  case Swap8 extends Opcode(0x97)
  case Swap9 extends Opcode(0x98)
  case Swap10 extends Opcode(0x99)
  case Swap11 extends Opcode(0x9a)
  case Swap12 extends Opcode(0x9b)
  case Swap13 extends Opcode(0x9c)
  case Swap14 extends Opcode(0x9d)
  case Swap15 extends Opcode(0x9e)
  case Swap16 extends Opcode(0x9f)
  case Log0 extends Opcode(0xa0)
  case Log1 extends Opcode(0xa1)
  case Log2 extends Opcode(0xa2)
  case Log3 extends Opcode(0xa3)
  case Log4 extends Opcode(0xa4)
  case Create extends Opcode(0xf0)
  case Call extends Opcode(0xf1)
  case CallCode extends Opcode(0xf2)
  case Return extends Opcode(0xf3)
  case DelegateCall extends Opcode(0xf4)
  case StaticCall extends Opcode(0xfa)
  case Revert extends Opcode(0xfd)
  case SelfDestruct extends Opcode(0xff)

object Opcode:

  /** The operation a byte names, or nothing where the byte names none.
    *
    * This is total over every value a byte can take and answers for the
    * VOCABULARY, so it is not a decoder: a byte with an answer here can still
    * be absent from the table a chain runs. [[OpcodeTable.operationAt]] is the
    * one that decides.
    */
  def fromCode(code: Int): Option[Opcode] = byCode.get(code)

  private val byCode: Map[Int, Opcode] = values.map(op => op.code -> op).toMap

  /** True where the operation carries its operand in the bytes following it,
    * which is the one shape that makes a byte of code not an operation.
    */
  def isPush(op: Opcode): Boolean = op.code >= Push1.code && op.code <= Push32.code

  /** How many bytes of code the operand occupies, and zero for everything that
    * takes none.
    */
  def immediateWidth(op: Opcode): Int = if isPush(op) then op.code - Push1.code + 1 else 0
