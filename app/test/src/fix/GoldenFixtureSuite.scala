package fix

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters._

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
}
