package org.fukuii.evm.fixtures

import java.nio.file.{Files, Path, Paths}

/** Where this project's own per-network fixtures are, which is not where the
  * published corpora are.
  *
  * ==A second root rather than a subdirectory of the first==
  *
  * [[FixtureCorpus]] locates third-party artifacts that are fetched and never
  * written. These fixtures are authored, and a tree that is being written to is
  * not the same kind of thing as one that is refreshed from upstream: resolving
  * one under the other would put a working repository inside a path whose whole
  * contract is that its contents came from somewhere else. The two roots are
  * therefore independent, and a report names which one it read.
  *
  * ==Two roots rather than one root answering a sequence==
  *
  * Answering with several paths would make every consumer state which of them a
  * finding came from, and a consumer that forgot would report a divergence
  * against no particular corpus. One name, one tree, and a caller that wants
  * both asks twice.
  *
  * ==Provenance, which is the reason a consumer of this must be careful==
  *
  * The published corpora are evidence from outside this project. These fixtures
  * are not: they are authored here, so agreement between them and this build is
  * only worth what the fixture's own oracle is worth. Every consumer records
  * the `_info.oracle` its expectations rest on, and a fixture whose oracle names
  * only the specification this build was written from is a weaker cross-check
  * than one naming an independent implementation or the chain itself.
  */
object NetworkFixtureCorpus:

  /** The directory holding one subdirectory per network family. */
  val RootVariable: String = "FUKUII_TESTS_ROOT"

  /** A file holding that same path, read when the variable is not set.
    *
    * The variable alone is not enough, for the reason [[FixtureCorpus]] states
    * about its own: a task run through an sbt server that was already running
    * sees the environment that server was started with, and a file is read at
    * the moment the corpus is wanted.
    */
  val RootPointer: Path = Paths.get(".local/fukuii-tests-root")

  def root: Option[Path] =
    sys.env
      .get(RootVariable)
      .orElse(sys.props.get(RootVariable))
      .orElse(pointed)
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .filter(Files.isDirectory(_))

  private def pointed: Option[String] =
    if Files.isRegularFile(RootPointer) then Some(Files.readString(RootPointer)) else None

  /** Where one network's fixtures sit, keyed the way the tree itself is.
    *
    * The family is the upstream organization and the network is its own name,
    * so a second network of the same family adds a sibling rather than a root.
    */
  def network(under: Path, family: String, name: String): Path =
    under.resolve("networks").resolve(family).resolve(name)

  /** The Ethereum Classic mainnet fixtures. */
  def classicMainnet(under: Path): Path = network(under, "ethereumclassic", "mainnet")
