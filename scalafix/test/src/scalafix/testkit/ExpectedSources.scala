package scalafix.testkit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Expected rewriter output, read from the `testOutput` tree so that the
  * compiler sees it.
  *
  * A unit test that asserts a rewrite against a string in its own source is
  * asserting that the renderer is *stable*, not that it is *correct*: nothing
  * ever compiles the string. `(using G: Functor)` -- which names a type that
  * does not exist -- lived in this suite's expectations until a real codebase
  * hit it, because every executed fixture goes through `testOutput.compile` and
  * these did not.
  *
  * Keeping the expectation in `testOutput/src` closes that gap: the same text
  * is both the assertion and a compilation unit.
  */
object ExpectedSources {

  private lazy val props: TestkitProperties =
    TestkitProperties.loadFromResources()

  def apply(relativePath: String): String = {
    val candidates = props.outputSourceDirectories.map(dir =>
      Paths.get(dir.toString, relativePath)
    )
    candidates
      .find(Files.isRegularFile(_))
      .map(path => new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
      .getOrElse(
        sys.error(
          s"no expected source '$relativePath'; looked in ${candidates.mkString(", ")}"
        )
      )
  }
}
