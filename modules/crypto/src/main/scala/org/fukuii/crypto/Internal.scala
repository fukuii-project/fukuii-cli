package org.fukuii.crypto

/** A mutable copy, for handing bytes to a Java library that takes `Array`.
  *
  * The copy is the price of the boundary, not of any specific callee's
  * behavior. `IArray` carries a compile-time immutability guarantee that cannot
  * be handed to an API typed on `Array`, and this module does not audit each
  * provider entry point for whether it happens to write to its argument —
  * a standing policy, because that answer is a property of a version rather
  * than of an interface, and it would have to be re-established on every bump.
  *
  * The cost is one allocation per call, which on a digest is the hot path.
  * Removing it for a specific call site means either an unchecked cast, which
  * this project's style rules forbid outright, or a per-version audit of the
  * callee — so it needs a measurement to justify it and does not have one.
  *
  * A caller with SECRET bytes should not reach for this at all: the copy
  * outlives the call and shows up in a heap dump. `ConstantTime` reads its
  * inputs directly for exactly that reason.
  */
private[crypto] def mutableCopy(source: IArray[Byte]): Array[Byte] =
  val out = new Array[Byte](source.length)
  var i   = 0
  while i < source.length do
    out(i) = source(i)
    i += 1
  out
