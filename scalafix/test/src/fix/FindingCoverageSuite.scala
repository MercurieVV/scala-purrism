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
    val dead = FindingCatalog.all
      .map(_.code)
      .filterNot(asserted)
      .filterNot(FindingCatalog.unfixturable.keySet)
    assert(
      dead.isEmpty,
      s"catalogued but never emitted in a fixture: ${dead.mkString(", ")}"
    )
  }
  test("every unfixturable code is catalogued and has no fixture") {
    val asserted = assertedCodes.keySet
    val notCatalogued =
      FindingCatalog.unfixturable.keySet.diff(FindingCatalog.byCode.keySet)
    assert(
      notCatalogued.isEmpty,
      s"unfixturable lists codes not in the catalog: ${notCatalogued.mkString(", ")}"
    )
    val nowFixtured = FindingCatalog.unfixturable.keySet.intersect(asserted)
    assert(
      nowFixtured.isEmpty,
      s"unfixturable codes that now have a fixture -- remove from the map: ${nowFixtured.mkString(", ")}"
    )
  }
  // Umbrella rules sum their children's patches, so scalafix prints the
  // umbrella's own name as the prefix (`[TypelevelPurrism.readability-budget]`)
  // -- see docs/MAINTENANCE.md "Finding codes". Kinds are unique across the
  // catalog, so such an assertion is catalogued when its kind is.
  private val umbrellaRules =
    Set("TypelevelPurrism", "PreferTypeParameters", "PreferCatsExpressions")
  private val kinds = FindingCatalog.all.map(_.kind).toSet

  private def catalogued(code: String): Boolean =
    FindingCatalog.byCode.contains(code) || {
      val (rule, kind) = code.span(_ != '.')
      umbrellaRules(rule) && kinds(kind.stripPrefix("."))
    }

  test("every asserted code is catalogued") {
    val unknown = assertedCodes.filterNot { case (c, _) => catalogued(c) }
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
