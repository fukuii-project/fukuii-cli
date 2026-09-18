package org.fukuii.evm

import com.sun.jna.ptr.IntByReference
import org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537

import org.fukuii.bytes.Bytes

/** EIP-2537's seven operations, through the native backend.
  *
  * ==One entry point, because the seven differ only in an opcode and a width==
  *
  * Each operation reads a fixed-shape input and writes a fixed-width answer, so
  * what varies between them is which native to call and how many bytes come
  * back. Seven wrappers would be seven copies of the same buffer handling, and
  * the buffer handling is where the hazards are.
  *
  * ==Two properties of the backend that are NOT obvious from its signatures==
  *
  * **The output buffer must be zero-initialized.** The binding says so in terms
  * -- *"The output buffer MUST be zero-initialized before calling this method.
  * The native implementation relies on this pre-initialization"* -- so a reused
  * buffer is a correctness question rather than a performance one. A fresh array
  * is allocated per call here, which is zero-filled by construction.
  *
  * **A refusal is a status code and a message, not an exception.** The native
  * returns non-zero and fills an error buffer; nothing throws. So the failure
  * path is a value to read rather than something to catch, and reading only the
  * status would discard what the corpus's negative cases are about.
  *
  * ==Every refusal is the same halt, and that is the proposal's own shape==
  *
  * The document has no partial answers: an input that is not a valid encoding,
  * a point off the curve, a point outside the subgroup and a length that is not
  * a whole number of pairs are all simply invalid, and the call fails. So this
  * reports one halt rather than a taxonomy the caller could not act on
  * differently.
  */
object Bls12:

  /** The sum of two points of the first group. */
  def g1Add(input: Bytes): Either[Halt, Bytes] =
    perform(LibGnarkEIP2537.BLS12_G1ADD_OPERATION_SHIM_VALUE, input, G1Width)

  /** A discounted sum of scaled points of the first group. */
  def g1Msm(input: Bytes): Either[Halt, Bytes] =
    perform(LibGnarkEIP2537.BLS12_G1MULTIEXP_OPERATION_SHIM_VALUE, input, G1Width)

  /** The sum of two points of the second group. */
  def g2Add(input: Bytes): Either[Halt, Bytes] =
    perform(LibGnarkEIP2537.BLS12_G2ADD_OPERATION_SHIM_VALUE, input, G2Width)

  /** A discounted sum of scaled points of the second group. */
  def g2Msm(input: Bytes): Either[Halt, Bytes] =
    perform(LibGnarkEIP2537.BLS12_G2MULTIEXP_OPERATION_SHIM_VALUE, input, G2Width)

  /** Whether the product of the stated pairings is the identity. */
  def pairing(input: Bytes): Either[Halt, Bytes] =
    perform(LibGnarkEIP2537.BLS12_PAIR_OPERATION_SHIM_VALUE, input, PairingWidth)

  /** A field element carried onto the first group. */
  def mapFpToG1(input: Bytes): Either[Halt, Bytes] =
    perform(LibGnarkEIP2537.BLS12_MAP_FP_TO_G1_OPERATION_SHIM_VALUE, input, G1Width)

  /** An extension-field element carried onto the second group. */
  def mapFp2ToG2(input: Bytes): Either[Halt, Bytes] =
    perform(LibGnarkEIP2537.BLS12_MAP_FP2_TO_G2_OPERATION_SHIM_VALUE, input, G2Width)

  /** A point of the first group, as the proposal encodes one. */
  val G1Width: Int = 128

  /** A point of the second group. */
  val G2Width: Int = 256

  /** The pairing's answer: a single word, one or zero. */
  val PairingWidth: Int = 32

  /** Whether the backend loaded at all.
    *
    * **Stated so a failure to load is distinguishable from a failure to
    * compute.** The binding catches its own loading failure and leaves a flag
    * rather than raising, so without this every call would look like a refused
    * input -- and a fork whose natives never loaded would read as a fork whose
    * every vector is invalid.
    */
  def available: Boolean = LibGnarkEIP2537.ENABLED

  private def perform(operation: Byte, input: Bytes, answerWidth: Int): Either[Halt, Bytes] =
    val raw = input.toIArray
    val supplied = new Array[Byte](raw.length)
    var index = 0
    while index < raw.length do
      supplied(index) = raw(index)
      index += 1
    // Fresh per call, so the zero-initialization the binding requires holds by
    // construction rather than by a caller remembering to clear one.
    val answer = new Array[Byte](LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_RESULT_BYTES)
    val message = new Array[Byte](LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_ERROR_BYTES)
    val answerLength = new IntByReference()
    val messageLength = new IntByReference()
    val status =
      LibGnarkEIP2537.eip2537_perform_operation(
        operation,
        supplied,
        supplied.length,
        answer,
        answerLength,
        message,
        messageLength
      )
    if status != 0 then Left(Halt.InvalidParameter)
    else Right(Bytes.fromArray(answer.take(answerWidth)))
