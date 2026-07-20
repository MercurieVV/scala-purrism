package fix.opaque

import java.nio.file.Files
import java.nio.file.Path

import scalafix.testkit.TestkitProperties

/** The compiled `testInput` module's SemanticDB payload, located the same way
  * the fixture runner locates it -- through `scalafix-testkit.properties` on
  * the test classpath.
  *
  * An earlier version resolved `out/scalafix/testInput/compile.dest/classes`
  * relative to the working directory. That path is correct from a shell but not
  * from the forked test JVM, so every test guarded on it skipped silently and
  * reported green. Locating the payload through the build-generated properties
  * removes the guess, and `require` turns a missing payload into a failure
  * instead of a vacuous pass.
  */
object FixtureIndex {

  private lazy val props: TestkitProperties =
    TestkitProperties.loadFromResources()

  lazy val classpath: List[Path] =
    props.inputClasspath.entries.map(_.toNIO)

  lazy val sourceroot: Path = props.sourceroot.toNIO

  lazy val index: SemanticdbIndex = {
    val payloads = classpath.filter(entry =>
      Files.isDirectory(entry.resolve("META-INF/semanticdb"))
    )
    require(
      payloads.nonEmpty,
      s"no META-INF/semanticdb under any of $classpath -- run mill scalafix.testInput.compile"
    )
    val loaded = SemanticdbIndex.load(classpath)
    require(
      loaded.documents.nonEmpty,
      s"SemanticDB payload found but empty under $payloads"
    )
    loaded
  }

  lazy val graph: Graph = new GraphBuilder(index, sourceroot).build()

  lazy val facts: Facts = GraphBuilder.facts(index, graph)
}
