package fix

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters._
import scala.meta._

final class GoldenFixtureSuite extends munit.FunSuite {
  test("golden fixtures keep one base file for each expected file") {
    val root = resourcePath("golden/typelevel")
    val baseFiles = fixtureFiles(root.resolve("base"))
    val expectedFiles = fixtureFiles(root.resolve("expected"))

    assertEquals(
      baseFiles.map(_.getFileName.toString),
      expectedFiles.map(_.getFileName.toString)
    )
    assert(baseFiles.nonEmpty, "at least one golden fixture is required")
  }

  test("golden fixtures document a concrete refactoring target") {
    val root = resourcePath("golden/typelevel")
    val base = read(root.resolve("base/01-io-service.scala"))
    val expected = read(root.resolve("expected/01-io-service.scala"))

    assert(clue(base).contains("cats.effect.IO"))
    assert(clue(expected).contains("MonadThrow"))
    assertNotEquals(base, expected)
  }

  test("kleisli rewrite converts a simple unary effect method") {
    val method = firstMethod(
      """def fetch(id: String): F[User] =
        |  client.get(id)""".stripMargin
    )

    assertEquals(
      TypelevelPurrism.kleisliRewrite(method),
      Some(
        """def fetch: Kleisli[F, String, User] =
          |  Kleisli.apply { id =>
          |    client.get(id)
          |  }""".stripMargin
      )
    )
  }

  test("kleisli rewrite skips methods with multiple parameters") {
    val method = firstMethod(
      """def fetch(id: String, retry: Boolean): F[User] =
        |  client.get(id)""".stripMargin
    )

    assertEquals(TypelevelPurrism.kleisliRewrite(method), None)
  }

  test("kleisli rewrite skips non-F unary return types") {
    val method = firstMethod(
      """def parse(id: String): Option[User] =
        |  Option.empty[User]""".stripMargin
    )

    assertEquals(TypelevelPurrism.kleisliRewrite(method), None)
  }

  private def resourcePath(name: String): Path =
    Path.of(getClass.getClassLoader.getResource(name).toURI)

  private def fixtureFiles(dir: Path): List[Path] =
    Files
      .list(dir)
      .iterator()
      .asScala
      .filter(path => path.getFileName.toString.endsWith(".scala"))
      .toList
      .sortBy(_.getFileName.toString)

  private def read(path: Path): String =
    Files.readString(path, StandardCharsets.UTF_8)

  private def firstMethod(source: String): Defn.Def =
    s"""final class Wrapper {
      |$source
       |}""".stripMargin
      .parse[Source]
      .get
      .stats
      .collectFirst { case cls: Defn.Class =>
        cls.templ.body.stats.collectFirst { case defn: Defn.Def => defn }.get
      }
      .get
}
