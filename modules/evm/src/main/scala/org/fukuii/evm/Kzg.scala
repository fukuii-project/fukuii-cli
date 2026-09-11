package org.fukuii.evm

import ethereum.ckzg4844.CKZG4844JNI

/** Whether a KZG proof shows that a committed polynomial takes a claimed value
  * at a claimed point.
  *
  * ==The one primitive here that is not bytecode, and the only one holding
  * state==
  *
  * Every other primitive this machine reaches for is a pure function over its
  * arguments. This one is a JNI wrapper around a C library that keeps the
  * ceremony output in a process-global `static`, so it has to be loaded before
  * any question can be asked of it and can be loaded only once. That is not a
  * property of this wrapper and cannot be designed away: it is where the
  * arithmetic lives.
  *
  * ==Loading is once per CLASS LOADER, which is the same grain the native
  * layer counts in==
  *
  * [[trustedSetup]] is a `lazy val`, so the JVM's own initialization lock makes
  * it run exactly once per class loader and makes every caller wait for the
  * first. That is the right grain rather than a convenient one, and it holds in
  * both directions:
  *
  *   - Where a class loader is REUSED, the value is already initialized and
  *     nothing is loaded a second time.
  *   - Where a FRESH one is created, `CKZG4844JNI.loadNativeLibrary` extracts
  *     the platform library to a newly created temporary directory and calls
  *     `System.load` on that path (`ethereum/c-kzg-4844` @ `v2.1.8`,
  *     `bindings/java/src/main/java/ethereum/ckzg4844/CKZG4844JNI.java:33-39`).
  *     A distinct path is a distinct library to the JVM, so the fresh loader
  *     gets its own copy of the C statics, with no setup loaded in it yet.
  *
  * **Both arrangements are ones a long-lived build server actually produces**,
  * which is why neither is left to chance. `KzgSpec` asserts the property that
  * matters -- that reaching this twice in one process is harmless -- rather
  * than asserting which of the two arrangements a given run took, which is not
  * this code's to decide.
  *
  * ==What the native layer does when that grain is got wrong, measured rather
  * than assumed==
  *
  * `bindings/java/ckzg_jni.c` at the same tag guards on the `settings` static:
  * loading over a loaded setup throws *"Trusted Setup is already loaded. Free
  * it before loading a new one."*, freeing an absent one throws *"Trusted Setup
  * is not loaded."*, and so does any verification attempted before a load.
  *
  * **That last one is the property that makes the failure safe.** A missing
  * setup raises rather than answering, so it cannot be mistaken for a proof
  * that failed to verify -- which is the one way this could have gone wrong
  * silently, and the reason the initialization below is allowed to throw
  * instead of being folded into [[verifyProof]]'s `None`.
  *
  * ==A load failure is FATAL and is deliberately not a refusal==
  *
  * Nothing here catches what initialization throws. A node whose ceremony
  * output is missing or unreadable cannot evaluate a point, and collapsing that
  * into a refused input would make it answer every invocation the way a
  * malformed one is answered -- a whole-chain divergence reported as a caller's
  * mistake. `org.fukuii.evm.Interpreter.runNatively` wraps neither
  * [[Precompile.gasFor]] nor [[Precompile.run]] in a handler, so the raise
  * reaches the caller.
  */
object Kzg:

  /** The ceremony output, as it sits on this module's own class path.
    *
    * ==What is shipped, and how a later reader checks it==
    *
    * 807,177 bytes, SHA-256
    * `d39b9f2d047cc9dca2de58f264b6a09448ccd34db967881a6713eacacf0f26b7`.
    * **Byte-identical to `ethereum/c-kzg-4844` @ `v2.1.8`,
    * `src/trusted_setup.txt`** -- the tag that names the same version as the
    * binding this build resolves -- and byte-identical to the copy
    * `besu-eth/besu` @ `b330564a9` (2026-09-11) ships at
    * `evm/src/main/resources/kzg-trusted-setups/mainnet.txt`.
    * `Consensys/teku`, `grandinetech/grandine` and c-kzg's own Java test
    * fixtures carry the same contents differing only in a trailing newline.
    *
    * **The digest is recorded here because the file itself cannot carry it.**
    * Its first line is parsed as a point count, so there is no comment syntax
    * to put a provenance header in.
    *
    * ==Two properties of the artifact that a reader should not have to infer==
    *
    * **It is NOT per-network.** One ceremony output serves every chain that
    * activates the document, which is why this sits beside the machine rather
    * than in any chain configuration.
    *
    * **Two formats are in circulation and this is the later one**, carrying the
    * G1 points in Lagrange form, then the G2 points, then the G1 points in
    * monomial form. A setup in the earlier format is about half the size, loads
    * without complaint against the wrong binding, and is the kind of substitution
    * `KzgPropSpec` exists to catch.
    *
    * ==The name is absolute AND package-scoped, which are two decisions==
    *
    * A resource name is shared by every jar in the process, so a bare
    * `/trusted-setup.txt` is a name another artifact can also define -- and
    * which copy wins is decided by class path order rather than by anything
    * here. For a file whose contents decide whether this client agrees with the
    * network, that is not a risk worth taking to save a directory.
    */
  private val SetupResource: String = "/org/fukuii/evm/kzg-trusted-setup.txt"

  /** How much of the setup is expanded at load time rather than per call.
    *
    * A memory-for-speed dial the library offers between 0 and 15, and it buys
    * nothing here: it accelerates the cell operations a later proposal
    * introduces, and point evaluation calls none of them. `besu-eth/besu` @
    * `b330564a9` (2026-09-11) passes the same 0 from
    * `evm/.../precompile/KZGPointEvalPrecompiledContract.java:89-90`.
    */
  private val Precompute: Long = 0

  /** The native library and the ceremony output, loaded once. See the class
    * note for why a `lazy val` is what expresses "once" correctly here, and why
    * a failure is left to raise.
    */
  private lazy val trustedSetup: Unit =
    CKZG4844JNI.loadNativeLibrary()
    CKZG4844JNI.loadTrustedSetupFromResource(SetupResource, getClass, Precompute)

  /** Whether the polynomial `commitment` commits to takes value `y` at point
    * `z`, or nothing where the arguments do not name points at all.
    *
    * ==Three answers, because the caller collapsing two of them must be the
    * one that can prove they collapse==
    *
    * `Some(true)` and `Some(false)` are a verification that ran. `None` is the
    * library declining the arguments -- a commitment or a proof that is not a
    * compressed point of the right group, or a field element at or above the
    * modulus. The published vector file names that third outcome separately
    * from the second, and `KzgSpec` reads all three from it.
    *
    * **EIP-4844's precompile answers `Some(false)` and `None` identically**,
    * and so do both clients read for it. That collapse is right THERE, where
    * every exceptional halt keeps nothing so the two cannot be told apart by a
    * caller. It would be wrong here: this is the layer that still knows which
    * happened, and a primitive that forgets cannot be asked again.
    *
    * The argument order is the specification's own --
    * `verify_kzg_proof(commitment, z, y, proof)` at
    * `ethereum/execution-specs` @ `0cc100eb1` (2026-09-11),
    * `src/ethereum/crypto/kzg.py` -- and is the order `besu-eth/besu` @
    * `b330564a9` passes at
    * `evm/.../precompile/KZGPointEvalPrecompiledContract.java:178-180`.
    * **Every one of the four is a byte string and two of them are the same
    * width**, so a transposition of `z` and `y` type-checks, runs, and answers
    * `false` for proofs that should hold.
    */
  def verifyProof(
      commitment: IArray[Byte],
      z: IArray[Byte],
      y: IArray[Byte],
      proof: IArray[Byte]
  ): Option[Boolean] =
    trustedSetup
    try
      Some(
        CKZG4844JNI.verifyKzgProof(
          mutableCopy(commitment),
          mutableCopy(z),
          mutableCopy(y),
          mutableCopy(proof)
        )
      )
    catch case _: RuntimeException => None

  /** The width a commitment and a proof each occupy, read from the library
    * rather than restated.
    */
  val PointWidth: Int = CKZG4844JNI.BYTES_PER_COMMITMENT

  /** The width a point and a claimed value each occupy, read from the library
    * rather than restated.
    */
  val FieldElementWidth: Int = CKZG4844JNI.BYTES_PER_FIELD_ELEMENT

  /** How many field elements one blob holds.
    *
    * Half of what the precompile answers with on success, and read from the
    * library so that the answer cannot disagree with the setup that produced
    * it -- the file's own first line states the same count.
    */
  val FieldElementsPerBlob: BigInt = BigInt(CKZG4844JNI.FIELD_ELEMENTS_PER_BLOB)

  /** The order of the scalar field the polynomial is defined over.
    *
    * The other half of the precompile's answer, read from the library for
    * [[FieldElementsPerBlob]]'s reason.
    */
  val BlsModulus: BigInt = BigInt(CKZG4844JNI.BLS_MODULUS)

  /** The library takes arrays it could write to, and this module holds
    * immutable ones. Copying at the boundary is what keeps that guarantee from
    * resting on a foreign library's good behavior.
    */
  private def mutableCopy(bytes: IArray[Byte]): Array[Byte] =
    val out = new Array[Byte](bytes.length)
    var index = 0
    while index < out.length do
      out(index) = bytes(index)
      index += 1
    out
