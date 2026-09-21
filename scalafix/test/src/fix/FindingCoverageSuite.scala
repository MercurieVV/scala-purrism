package fix

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

final class FindingCoverageSuite extends munit.FunSuite {
  private val inputRoot: Path = Paths.get(
    sys.props.getOrElse("purrism.testInput", "scalafix/testInput/src")
  )
  private val marker = """//\s*assert:\s*([A-Z][A-Za-z0-9]*\.[a-z0-9-]+)""".r

  private def assertedCodes: Map[String, List[String]] = // code -> files
    Files
      .walk(inputRoot)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .toList
      .flatMap { p =>
        marker
          .findAllMatchIn(Files.readString(p))
          .map(m => m.group(1) -> p.toString)
          .toList
      }
      .groupMap(_._1)(_._2)

  test("every catalogued code is asserted by at least one fixture") {
    val asserted = assertedCodes.keySet
    val dead = FindingCatalog.all.map(_.code).filterNot(asserted)
    assert(
      dead.isEmpty,
      s"catalogued but never emitted in a fixture: ${dead.mkString(", ")}"
    )
  }
  test("every asserted code is catalogued") {
    val unknown = assertedCodes.filterNot { case (c, _) =>
      FindingCatalog.byCode.contains(c)
    }
    assert(
      unknown.isEmpty,
      s"fixtures assert codes the catalog does not know: ${unknown.mkString(", ")}"
    )
  }
  test("no fixture still asserts a bare rule name on a purrism lint") {
    val bare = """//\s*assert:\s*([A-Z][A-Za-z0-9]*)\s*$""".r
    val rules = FindingCatalog.all.map(_.rule).toSet
    val hits = Files
      .walk(inputRoot)
      .iterator()
      .asScala
      .filter(_.toString.endsWith(".scala"))
      .toList
      .flatMap { p =>
        Files.readAllLines(p).asScala.zipWithIndex.collect {
          case (l, i)
              if bare.findFirstMatchIn(l).exists(m => rules(m.group(1))) =>
            s"$p:${i + 1}"
        }
      }
    assert(
      hits.isEmpty,
      s"bare-rule assertions on purrism lints (need Rule.kind): ${hits.mkString(", ")}"
    )
  }
}
