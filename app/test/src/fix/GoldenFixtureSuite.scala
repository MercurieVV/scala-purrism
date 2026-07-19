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

  test("kleisli rewrite composes direct Kleisli apply calls") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  fetch(id).flatMap(user => profile(user))""".stripMargin
    )

    assertEquals(
      TypelevelPurrism.kleisliRewrite(method, Set("fetch", "profile")),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  fetch.andThen(profile)""".stripMargin
      )
    )
  }

  test("kleisli rewrite composes explicit Kleisli run calls") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  fetch.run(id).flatMap(user => profile.run(user))""".stripMargin
    )

    assertEquals(
      TypelevelPurrism.kleisliRewrite(method),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  fetch.andThen(profile)""".stripMargin
      )
    )
  }

  test("kleisli rewrite composes inside an existing Kleisli") {
    val method = firstMethod(
      """def loadProfile: Kleisli[F, String, Profile] =
        |  Kleisli.apply { id =>
        |    fetch(id).flatMap(user => profile(user))
        |  }""".stripMargin
    )

    assertEquals(
      TypelevelPurrism.kleisliRewrite(method, Set("fetch", "profile")),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  fetch.andThen(profile)""".stripMargin
      )
    )
  }

  test("kleisli rewrite splits setup before a final Kleisli call") {
    val method = firstMethod(
      """def loadProfile: Kleisli[F, String, Profile] =
        |  Kleisli.apply { id =>
        |    val user = User(id)
        |    profile.run(user)
        |  }""".stripMargin
    )

    assertEquals(
      TypelevelPurrism.kleisliRewrite(method),
      Some(
        """def loadProfile: Kleisli[F, String, Profile] =
          |  Kleisli.apply { id =>
          |    val user = User(id)
          |    user
          |  }.andThen(profile)""".stripMargin
      )
    )
  }

  test("kleisli rewrite skips direct calls that are not known Kleislies") {
    val method = firstMethod(
      """def loadProfile(id: String): F[Profile] =
        |  client.get(id).flatMap(user => profile.save(user))""".stripMargin
    )

    assertEquals(TypelevelPurrism.kleisliCompositionRewrite(method), None)
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
