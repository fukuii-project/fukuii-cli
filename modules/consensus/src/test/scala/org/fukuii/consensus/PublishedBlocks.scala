package org.fukuii.consensus

import scala.util.{Failure, Success, Try}

import org.fukuii.bytes.{Address, Bytes, Hash}
import org.fukuii.evm.fixtures.FixtureAccount
import org.fukuii.rlp.RlpCodec
import org.fukuii.types.{Block, BlockHeader}

/** One published block, the genesis it builds on, and the accounts that genesis
  * holds, together with the two hashes the release states for them.
  *
  * @param publishedGenesisHash
  *   what the release states the genesis header hashes to. A transcription that
  *   decodes to a different header answers a different hash.
  * @param publishedBlockHash
  *   likewise for the block, whose own header is what carries every commitment
  *   a validator compares.
  */
final case class PublishedBlock(
    genesis: BlockHeader,
    block: Block,
    pre: Map[Address, FixtureAccount],
    publishedGenesisHash: Hash,
    publishedBlockHash: Hash
)

/** Blocks taken verbatim from the published blockchain fixtures, each with the
  * pre-state its genesis commits to.
  *
  * ==Why these and not a block built here==
  *
  * A block assembled in this tree could only carry commitments this tree
  * computed, so a validator accepting it would be agreeing with itself. Every
  * commitment in these headers was produced by the tool that filled the
  * fixture, so accepting one is evidence about the derivations rather than
  * about their consistency with each other.
  *
  * ==Where they come from==
  *
  * `ethereum/execution-specs-fixtures` at the `tests-v20.0.1` release,
  * `blockchain_tests`, one case each:
  *
  *   - [[withdrawalToCreatedContract]], from
  *     `for_cancun/shanghai/eip4895_withdrawals/withdrawals/newly_created_contract.json`,
  *     `test_newly_created_contract[fork_Cancun-blockchain_test-with_tx_value]`
  *   - [[logsFromCalls]], from
  *     `for_cancun/frontier/opcodes/all_opcodes/constant_gas.json`,
  *     `test_constant_gas[fork_Cancun-LOG1-blockchain_test_from_state_test]`
  *   - [[oneBlob]], from
  *     `for_cancun/cancun/eip4844_blobs/blob_txs/valid_blob_tx_combinations.json`,
  *     `test_valid_blob_tx_combinations[fork_Cancun-blobs_per_tx_(1,)-blockchain_test-block_base_fee_per_gas_7]`
  *
  * **This is not the corpus being wired in.** These are hand-picked cases whose
  * bytes were copied into source by a script rather than by hand, and every
  * literal is checked against a value the release states beside it -- the
  * header against its hash and the accounts against the genesis root -- so a
  * copying error fails a check that names it rather than a verdict that does
  * not.
  *
  * ==Every literal is decoded into an answer, never into a raise==
  *
  * Each case is an `Either` naming the first literal that did not decode, and
  * nothing here throws while this object initializes. A literal decoded
  * eagerly by a partial accessor would abort every suite reading this object
  * at once, which a run's summary reports only as fewer tests; decoded into
  * an answer, it fails the tests that read the block, and names the literal.
  *
  * ==Three blocks rather than one, and each covers what the others cannot==
  *
  * No published Cancun block carries a transaction, a withdrawal, a log and a
  * blob at once. The first carries a withdrawal but states an empty bloom and
  * no blob gas, so a derivation that answered a constant for either would still
  * accept it. The second states a bloom over three logs and the third a blob's
  * worth of gas, which is what separates those derivations from constants.
  *
  * All three genesis states hold the beacon-roots contract, so the call a
  * block makes before its transactions moves every root below.
  */
object PublishedBlocks:

  /** One pre-state account, as the release states its four fields. */
  final private case class AccountLiteral(address: String, nonce: String, balance: String, code: String)

  /** `hex` through `parse`, or a message naming `what` where it refused or raised. */
  private def decoded[A](what: String, hex: String)(parse: String => Either[Any, A]): Either[String, A] =
    Try(parse(hex)) match
      case Success(Right(value)) => Right(value)
      case Success(Left(error))  => Left(what + " did not decode: " + error.toString)
      case Failure(raised)       => Left(what + " did not decode: " + raised.toString)

  private def quantityOf(hex: String): Either[Any, BigInt] = Right(BigInt(hex.stripPrefix("0x"), 16))

  /** A whole block's encoding, as the release publishes a block and a genesis. */
  private def blockOf(hex: String): Either[Any, Block] =
    Bytes
      .fromHex(hex)
      .left
      .map(_.toString)
      .flatMap(bytes => RlpCodec.decodeFrom[Block](bytes.toIArray).left.map(_.toString))

  private def account(literal: AccountLiteral): Either[String, (Address, FixtureAccount)] =
    val what = "account " + literal.address
    for
      address <- decoded(what, literal.address)(Address.fromHex)
      nonce <- decoded(what + " nonce", literal.nonce)(quantityOf)
      balance <- decoded(what + " balance", literal.balance)(quantityOf)
      code <- decoded(what + " code", literal.code)(Bytes.fromHex)
    yield address -> FixtureAccount(nonce, balance, code, Map.empty)

  private def published(
      genesis: String,
      block: String,
      pre: Seq[AccountLiteral],
      genesisHash: String,
      blockHash: String
  ): Either[String, PublishedBlock] =
    for
      genesisBlock <- decoded("the genesis block", genesis)(blockOf)
      decodedBlock <- decoded("the block", block)(blockOf)
      accounts <- pre.foldLeft[Either[String, Map[Address, FixtureAccount]]](Right(Map.empty)) { (held, literal) =>
        held.flatMap(map => account(literal).map(map + _))
      }
      statedGenesisHash <- decoded("the genesis hash", genesisHash)(Hash.fromHex)
      statedBlockHash <- decoded("the block hash", blockHash)(Hash.fromHex)
    yield PublishedBlock(genesisBlock.header, decodedBlock, accounts, statedGenesisHash, statedBlockHash)

  /** The account every genesis below deploys EIP-4788's contract at. */
  private val BeaconRoots: String = "0x000f3df6d732807ef1319fb7b8bb8522d0beac02"

  /** Its code, byte-identical in all three releases' pre-states. */
  private val BeaconRootsCode: String =
    "0x3373fffffffffffffffffffffffffffffffffffffffe14604d57602036146024575f5ffd5b5f35801560495762001f" +
      "ff810690815414603c575f5ffd5b62001fff01545f5260205ff35b5f5ffd5b62001fff42064281555f359062001fff01" +
      "5500"

  val withdrawalToCreatedContract: Either[String, PublishedBlock] = published(
    genesis = "0xf9023cf90236a00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d" +
      "7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347940000000000000000000000000000000000000000a0b4" +
      "3327605e4860e992609d8eea181642b2ee2ff9fbe0af474d741c068c580fa0a056e81f171bcc55a6ff8345e692c0f86e" +
      "5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4" +
      "21b901000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000080808407270e00808000a00000000000000000000000000000000000" +
      "00000000000000000000000000000088000000000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996c" +
      "adc001622fb5e363b4218080a00000000000000000000000000000000000000000000000000000000000000000c0c0c0",
    block = "0xf902aff90238a009099c09f736f55edbaf5b645559fb142a22490c04e60a63c817f24b7e237be4a01dcc4de8dec75d" +
      "7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa0f4" +
      "c0b10345bf5c1fe6911ca270f2347aaff63395512356850ec16ba7332f0be2a011994ca4407638933d756822df23e173" +
      "efbc854d09509c61b9ff683c25681544a06363485005a5cae5b903c3085b077d8b668552fb8aa354458f3966fe5aaf87" +
      "dab901000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000080018407270e0082d01f0c80a0000000000000000000000000000000" +
      "000000000000000000000000000000000088000000000000000007a0512b9020737ea6860d4b491ed2da6080c9fb37e9" +
      "d9cb6c10c6f2b2a2777a84388080a00000000000000000000000000000000000000000000000000000000000000000f8" +
      "57f855800a830f424080843b9aca008560016000f325a09425819d97c4711b987424f7d6866c8df9f77d8623d5152dba" +
      "b8308bb323d8bda00cae22d734edd3fb916c53e4dd12a851683dc9db1ce78c1e8bd506e88d7f988ec0d9d8808094081f" +
      "85bbb63f960f83724898686620b3e01a554201",
    pre = Seq(
      AccountLiteral(
        address = BeaconRoots,
        nonce = "0x01",
        balance = "0x00",
        code = BeaconRootsCode
      ),
      AccountLiteral(
        address = "0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff",
        nonce = "0x00",
        balance = "0x033b2e3c9fd0803ce8000000",
        code = "0x"
      )
    ),
    genesisHash = "0x09099c09f736f55edbaf5b645559fb142a22490c04e60a63c817f24b7e237be4",
    blockHash = "0xded9d03ef643858724438da11038745c9d18ab51d23868bcc25b278351db0d24"
  )

  val logsFromCalls: Either[String, PublishedBlock] = published(
    genesis = "0xf9023cf90236a00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d" +
      "7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347940000000000000000000000000000000000000000a03b" +
      "dc0ada1be0e92c5ea13e6ba5586b82eb3b268ef278892c3415052db15f884da056e81f171bcc55a6ff8345e692c0f86e" +
      "5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4" +
      "21b901000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000080808407270e00808000a00000000000000000000000000000000000" +
      "00000000000000000000000000000088000000000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996c" +
      "adc001622fb5e363b4218080a00000000000000000000000000000000000000000000000000000000000000000c0c0c0",
    block = "0xf902a5f9023ba004248b63148d2ae0b3b60414b419af0fcedf4fda5fd2a69ee391162e75dc5837a01dcc4de8dec75d" +
      "7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa014" +
      "b3a72f5b792fd7834cfce01a81c760979a34c15ca0b868ba44dddd699619c7a0a8e9a98c1ed2dc5b781ee40d0d11f372" +
      "4efd8cc00d19424f7f9143b95bac51eea0ff8497d402d1243a020a7e60088aa6c9d651aade3967611ef18f429327f2b9" +
      "14b901000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000100000000008002000000000000000000080000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000800" +
      "000000000000000000000000000000000000000080018407270e00830180b98203e800a0000000000000000000000000" +
      "000000000000000000000000000000000000000088000000000000000007a056e81f171bcc55a6ff8345e692c0f86e5b" +
      "48e01b996cadc001622fb5e363b4218080a0000000000000000000000000000000000000000000000000000000000000" +
      "0000f863f861800a8407270e0094e500bc4e9bd8c4a6f74dd3b56286ef9b32103be7808025a05b618736382b5d20f14d" +
      "972963e4c12f44b55fada377aaac7a353cf1aaf70533a030126c7758ba35de8cc302279d9ed440807c1c67f6c3e96bad" +
      "f5a99bd31630efc0c0",
    pre = Seq(
      AccountLiteral(
        address = BeaconRoots,
        nonce = "0x01",
        balance = "0x00",
        code = BeaconRootsCode
      ),
      AccountLiteral(
        address = "0xf6c3a9edc1afa0ad5b720e4d42e1437c43d3b3ff",
        nonce = "0x00",
        balance = "0x033b2e3c9fd0803ce8000000",
        code = "0x"
      ),
      AccountLiteral(
        address = "0x177effe857ce54c285cd17403e11fb6b61eeba94",
        nonce = "0x01",
        balance = "0x00",
        code = "0x6000515060006000600000"
      ),
      AccountLiteral(
        address = "0x57ae37b6a9d50c861278223e008bba1b8e055279",
        nonce = "0x01",
        balance = "0x00",
        code = "0x60005150600060006000a100"
      ),
      AccountLiteral(
        address = "0xe500bc4e9bd8c4a6f74dd3b56286ef9b32103be7",
        nonce = "0x01",
        balance = "0x00",
        code = "0x7357ae37b6a9d50c861278223e008bba1b8e055279315073177effe857ce54c285cd17403e11fb6b61eeba9431505a" +
          "6000600060006000600073177effe857ce54c285cd17403e11fb6b61eeba945af1505a90035a60006000600060006000" +
          "7357ae37b6a9d50c861278223e008bba1b8e0552795af1505a90035a600060006000600060007357ae37b6a9d50c8612" +
          "78223e008bba1b8e0552795af1505a90038290036100015581900361000055600060006000600060007357ae37b6a9d5" +
          "0c861278223e008bba1b8e0552798661027101f1600255600060006000600060007357ae37b6a9d50c861278223e008b" +
          "ba1b8e0552798661027201f160035500"
      )
    ),
    genesisHash = "0x04248b63148d2ae0b3b60414b419af0fcedf4fda5fd2a69ee391162e75dc5837",
    blockHash = "0xcc23c37ab12f6698fde1fe77efc3f380a1a54db113e739601d1283b2dd973b81"
  )

  val oneBlob: Either[String, PublishedBlock] = published(
    genesis = "0xf9023ff90239a00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d" +
      "7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347940000000000000000000000000000000000000000a0eb" +
      "098ece750f459bf13084b067908bbe2ae54bc1d1ec6664c29107b15a96ab76a056e81f171bcc55a6ff8345e692c0f86e" +
      "5b48e01b996cadc001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4" +
      "21b901000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000080808407270e00808000a00000000000000000000000000000000000" +
      "00000000000000000000000000000088000000000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996c" +
      "adc001622fb5e363b4218083140000a00000000000000000000000000000000000000000000000000000000000000000" +
      "c0c0c0",
    block = "0xf902cff9023ea0537d6cae17c940343b285c43a17495401f7d7b8e28ed98711fe5a1b49765f871a01dcc4de8dec75d" +
      "7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347942adc25665018aa1fe0e6bc666dac8fc2697ff9baa07d" +
      "887433324983d6b2774385c5e71b32af89d78dd30a3dba943ddad1dd50cd70a017170e33a39ea49b0a1635363d92c216" +
      "ab0b3110efbc41dc11d7f02028764c31a0eaa8c40899a61ae59615cf9985f5e2194f8fd2b57d273be63bde6733e89b12" +
      "abb901000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000000000000000000000000000000000000000080018407270e008252080c80a0000000000000000000000000000000" +
      "000000000000000000000000000000000088000000000000000007a056e81f171bcc55a6ff8345e692c0f86e5b48e01b" +
      "996cadc001622fb5e363b42183020000830e0000a0000000000000000000000000000000000000000000000000000000" +
      "0000000000f88ab88803f8850180800782520894c0f6dc9e5836f54caadbf59cc69346c508e1992b0180c001e1a00100" +
      "00000000000000000000000000000000000000000000000000000000000080a0850fd8b9f3ccae6ecf0661550f5549f9" +
      "baf6ae87eb9452460370d91dfa26f4fba07d675116c69b1fa49c1c221c5d626220205f7ccd8652df673440dae7c5d289" +
      "9bc0c0",
    pre = Seq(
      AccountLiteral(
        address = BeaconRoots,
        nonce = "0x01",
        balance = "0x00",
        code = BeaconRootsCode
      ),
      AccountLiteral(
        address = "0x7463e5d05012f4a5ad39ccfc9d91b8783f99ce0a",
        nonce = "0x00",
        balance = "0x043e39",
        code = "0x"
      )
    ),
    genesisHash = "0x537d6cae17c940343b285c43a17495401f7d7b8e28ed98711fe5a1b49765f871",
    blockHash = "0x5e079208b9a9ef0158a4827bb24024c70b4df13c23f218518ec194e7fc4b9ed0"
  )
