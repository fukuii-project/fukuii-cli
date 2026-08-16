// The formatter. It reaches the metabuild classpath only — never a module's
// compile or test classpath, and nothing it brings ships in a published
// artifact.
//
// The formatting ENGINE is not pinned here. `scalafmt-core` is resolved
// dynamically from `.scalafmt.conf`'s own `version` key, so the two move
// independently and this coordinate says nothing about which engine formats
// the tree. Read that version from `.scalafmt.conf`, not from this file.
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
