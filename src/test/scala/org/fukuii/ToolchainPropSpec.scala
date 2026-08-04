package org.fukuii

import org.scalatest.propspec.AnyPropSpec
import org.scalatest.prop.TableDrivenPropertyChecks

/** Proves the `scalatest-propspec` artifact resolves, and that
  * `TableDrivenPropertyChecks` reaches it from `scalatest-core` transitively
  * rather than needing an artifact of its own.
  *
  * That second half is not guessable from the coordinates and is the reason
  * AGENTS.md's "test matrices" use case needs no further `libraryDependencies`
  * entry: `TableDrivenPropertyChecks` ships in `scalatest-core`, which arrives
  * with any style artifact.
  *
  * The shared warrant, and the retirement trigger, are in [[ToolchainFlatSpec]].
  */
class ToolchainPropSpec extends AnyPropSpec with TableDrivenPropertyChecks:

  /** A table of named vectors — the shape AGENTS.md assigns to PropSpec, rather
    * than a generated property, so this exercises the matrix use case exactly.
    */
  private val squares = Table(
    ("input", "expected"),
    (0, 0),
    (1, 1),
    (7, 49),
    (12, 144)
  )

  property("the propspec artifact drives a table of named vectors") {
    forAll(squares) { (input: Int, expected: Int) =>
      assert(input * input == expected, s"square of $input, expected $expected")
    }
  }
