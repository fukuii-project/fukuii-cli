package org.fukuii.types

import org.fukuii.bytes.{Address, Bytes}

/** The code an account holds when it has delegated its behavior to another.
  *
  * ==A designation is CODE, which is the whole of EIP-7702's design==
  *
  * The document gives an externally owned account code rather than giving it a
  * new kind of state. What it stores is three marker bytes followed by an
  * address, and every rule that reads code reads that -- so an account with a
  * designation is, to everything below this type, an account with 23 bytes of
  * code.
  *
  * **That is why this is a reading of bytes and not a field.** Nothing in the
  * account record says "delegated"; the answer comes from looking at the code,
  * which is what makes a designation writable by the ordinary code-setting path
  * and readable by every layer that already loads code.
  *
  * ==The marker is a reserved prefix, and the reservation predates this
  * document==
  *
  * `0xef` was made unusable as a first code byte by EIP-3541, which refuses to
  * deploy anything starting with it. That is what lets this document spend the
  * prefix without colliding with deployed code: no account can hold code
  * beginning `0xef` unless something other than a deployment put it there.
  *
  * ==Width is exact, not a minimum==
  *
  * `ethereum/execution-specs` @ `0cc100eb1`,
  * `src/ethereum/forks/prague/vm/eoa_delegation.py:50-55` requires the length to
  * equal the marker plus an address, so code that merely STARTS with the marker
  * is not a designation. A prefix test would read a longer stretch of code as a
  * designation and follow an address out of the middle of it.
  */
object Delegation:

  /** `0xef0100`, the three bytes a designation begins with. */
  val Marker: IArray[Byte] = IArray(0xef.toByte, 0x01.toByte, 0x00.toByte)

  /** Marker plus address: 3 + 20. Code of any other length is not a
    * designation, however it begins.
    */
  val Width: Int = Marker.length + Address.Width

  /** The code an account holds while it delegates to `target`. */
  def designating(target: Address): Bytes =
    Bytes.fromIArray(Marker ++ target.toBytes)

  /** The account `code` delegates to, or nothing where it is not a designation.
    *
    * **Both halves are checked**: the exact width and the exact prefix. Reading
    * the address without checking the width would take twenty bytes from
    * wherever the code happened to end.
    */
  def targetOf(code: Bytes): Option[Address] =
    val raw = code.toIArray
    if raw.length != Width then None
    else if !prefixMatches(raw) then None
    else Address.fromBytes(raw.drop(Marker.length)).toOption

  /** Whether `code` is a designation, which is [[targetOf]] without the
    * address.
    */
  def isDesignation(code: Bytes): Boolean = targetOf(code).isDefined

  private def prefixMatches(raw: IArray[Byte]): Boolean =
    var index = 0
    var matches = true
    while index < Marker.length do
      if raw(index) != Marker(index) then matches = false
      index += 1
    matches
