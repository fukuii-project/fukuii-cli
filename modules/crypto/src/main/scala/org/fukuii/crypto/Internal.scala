package org.fukuii.crypto

/** A mutable copy, for handing bytes to a Java library that takes `Array`.
  *
  * The copy is not incidental. `IArray` carries a compile-time immutability
  * guarantee that the callee does not honor, and several of the primitives here
  * take a buffer they are entitled to overwrite. Copying at the boundary is what
  * keeps a caller's value from being mutated underneath it.
  */
private[crypto] def mutableCopy(source: IArray[Byte]): Array[Byte] =
  val out = new Array[Byte](source.length)
  var i   = 0
  while i < source.length do
    out(i) = source(i)
    i += 1
  out
