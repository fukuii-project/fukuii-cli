package org.fukuii.evm

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537
import org.scalatest.flatspec.AnyFlatSpec

/** The backend's jar is the one that was reviewed.
  *
  * ==Why this exists, and why a hash alone would not be enough==
  *
  * The artifact is not on Maven Central and carries no signature -- the
  * publishing host serves a checksum but nothing attesting who built it. So
  * there are two questions, and each needs its own answer:
  *
  *   - **are these the bytes that were reviewed?** This file, by digest.
  *   - **do those bytes compute the right answers?** [[Bls12PropSpec]], by
  *     running a third-party vector corpus through them.
  *
  * **Neither substitutes for the other.** A digest says nothing about whether
  * the code is correct, and a vector run passes over any build that happens to
  * compute correctly, including one nobody chose. Together they say: these exact
  * bytes, and they are right.
  *
  * This is the pattern the trusted-setup resource already uses one layer down --
  * a digest pinned beside a behavioral check -- applied to a resolved dependency
  * rather than to a bundled file.
  *
  * ==Both assertions survive the artifact being replaced==
  *
  * When this dependency moves to one published under this project's own
  * coordinates, the digest changes and this test fails loudly, which is the
  * correct behavior: the new artifact has to be reviewed and re-pinned rather
  * than silently accepted. The vector run carries over unchanged.
  */
class Bls12ArtifactSpec extends AnyFlatSpec:

  /** The digest of `gnark-2.0.0.jar` as served by the publishing host, which
    * also reports it as `X-Checksum-Sha256` on a plain request for the file.
    */
  private val Pinned: String = "9632cf125816fda1968886ef2fa0a8620cdf2ebf8b193968b66c31796bc5095e"

  /** Where the resolved jar actually sits, asked of the class rather than
    * guessed from a cache layout that varies by resolver and machine.
    */
  private def resolvedJar: Option[Path] =
    Option(classOf[LibGnarkEIP2537].getProtectionDomain)
      .flatMap(domain => Option(domain.getCodeSource))
      .flatMap(source => Option(source.getLocation))
      .map(location => Paths.get(location.toURI))
      .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".jar"))

  private def sha256Of(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(Files.readAllBytes(path))
    digest.digest().map(b => f"${b & 0xff}%02x").mkString

  "the backend jar" should "be locatable from the class that loads it" in
    // The instrument check. Were the jar not findable, the digest assertion
    // below would be skipped and report nothing -- so its absence is stated as
    // a failure here rather than silently weakening the check that follows.
    assert(
      resolvedJar.isDefined,
      "the resolved artifact could not be located, so the digest below checks nothing"
    )

  it should "be the exact artifact that was reviewed" in
    assert(
      resolvedJar.map(sha256Of).contains(Pinned),
      "the resolved jar's digest differs from the one pinned here: " +
        resolvedJar.map(sha256Of).getOrElse("no jar found") + " against " + Pinned
    )

  it should "have loaded its native library" in
    // A jar with the right digest whose natives did not load would pass the
    // check above and compute nothing. The backend reports its own loading
    // separately from any call, because it catches the failure rather than
    // raising it.
    assert(
      Bls12.available,
      "the artifact is the right one and its native library did not load"
    )
