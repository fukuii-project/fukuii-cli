package org.fukuii.bytes

import scala.annotation.tailrec

/** A reason a hex string could not be decoded.
  *
  * Decoding runs on input this node did not author — a peer's message, an
  * operator's configuration, a file on disk — so the failure is part of the
  * return type rather than an exception a caller has no signature-level reason
  * to expect.
  */
enum HexError:
  case OddLength(length: Int)
  case NotHex(char: Char, index: Int)

/** Hexadecimal encoding and decoding for byte sequences. */
object Hex:

  /** The longest input `encode` can represent. Two characters per byte, and a
    * `String` is backed by an array whose length is an `Int`.
    */
  val MaxEncodable: Int = Int.MaxValue / 2

  /** Encodes to lowercase without a `0x` prefix.
    *
    * The prefix is left to whoever renders the value: adding it here would
    * force every consumer that does not want one to strip it back off.
    *
    * @throws IllegalArgumentException
    *   for an input longer than [[MaxEncodable]]. The result is unrepresentable
    *   at that size whatever this returns, so the failure is not avoidable — but
    *   `length * 2` overflows to a negative before it is unrepresentable, which
    *   surfaces as a `NegativeArraySizeException` naming nothing. This states
    *   what happened instead. It is not part of the ordinary contract: every
    *   caller in this module encodes a value of bounded width.
    */
  def encode(bytes: IArray[Byte]): String =
    require(bytes.length <= MaxEncodable, "hex encoding needs two chars per byte; input is too long to represent")
    val out = new Array[Char](bytes.length * 2)
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      out(i * 2) = Digits(b >>> 4)
      out(i * 2 + 1) = Digits(b & 0x0f)
      i += 1
    new String(out)

  /** Decodes, accepting an optional `0x` prefix and either case.
    *
    * All three forms appear in specifications, client output and configuration.
    * Anything else is rejected: a decoder that skips an unexpected character
    * turns a corrupt value into a plausible one.
    */
  def decode(s: String): Either[HexError, IArray[Byte]] =
    val body =
      if s.length >= 2 && s.charAt(0) == '0' && (s.charAt(1) == 'x' || s.charAt(1) == 'X') then s.substring(2)
      else s
    if body.length % 2 != 0 then Left(HexError.OddLength(body.length))
    else fill(body, new Array[Byte](body.length / 2), 0)

  private val Digits = "0123456789abcdef"

  @tailrec
  private def fill(body: String, out: Array[Byte], i: Int): Either[HexError, IArray[Byte]] =
    if i >= body.length then Right(IArray.unsafeFromArray(out))
    else
      val hi = nibble(body.charAt(i))
      val lo = nibble(body.charAt(i + 1))
      if hi < 0 then Left(HexError.NotHex(body.charAt(i), i))
      else if lo < 0 then Left(HexError.NotHex(body.charAt(i + 1), i + 1))
      else
        out(i / 2) = ((hi << 4) | lo).toByte
        fill(body, out, i + 2)

  /** Returns -1 for a non-hex character rather than an `Option`, because this
    * runs twice per output byte and the allocation would be the dominant cost.
    */
  private def nibble(c: Char): Int =
    if c >= '0' && c <= '9' then c - '0'
    else if c >= 'a' && c <= 'f' then c - 'a' + 10
    else if c >= 'A' && c <= 'F' then c - 'A' + 10
    else -1
