package fix

import java.nio.file.{Files, Path, Paths}
import scala.meta.inputs.{Input, Position}

final class FindingCatalogSuite extends munit.FunSuite {
  private val sample = Finding(
    "PreferEffectIdioms",
    "manual-resource",
    "Manual acquire/release",
    "A resource acquired and released by hand is not tracked by the effect system.",
    "Wrap the acquire in Resource.make(acquire)(release) and thread it with use.",
    "#effect-boundaries"
  )
  private val pos = Position.Range(Input.String("x"), 0, 1)

  test("code is rule.kind") {
    assertEquals(sample.code, "PreferEffectIdioms.manual-resource")
  }
  test("message appends the explanation after a period") {
    assertEquals(
      FindingDiagnostic(
        sample,
        pos,
        "use Resource for acquire/release"
      ).message,
      "use Resource for acquire/release. A resource acquired and released by hand is not tracked by the effect system."
    )
  }
  test("message does not double a trailing period") {
    assertEquals(
      FindingDiagnostic(sample, pos, "already ends.").message,
      "already ends. A resource acquired and released by hand is not tracked by the effect system."
    )
  }
  test("categoryID is the kind, so scalafix prints [Rule.kind]") {
    assertEquals(
      FindingDiagnostic(sample, pos, "t").categoryID,
      "manual-resource"
    )
  }
  test("every catalogued code is well-formed and unique") {
    val codes = FindingCatalog.all.map(_.code)
    assertEquals(codes, codes.distinct, "duplicate codes")
    codes.foreach(c =>
      assert(
        c.matches("""^[A-Z][A-Za-z0-9]*\.[a-z0-9]+(-[a-z0-9]+)*$"""),
        s"malformed code $c"
      )
    )
    assertEquals(codes, codes.sorted, "catalog must be sorted by code")
  }
  test(
    "every field is non-empty, TAB-free, newline-free; title <= 60; explanation ends with a period"
  ) {
    FindingCatalog.all.foreach { f =>
      List(f.rule, f.kind, f.title, f.explanation, f.instruction, f.doc)
        .foreach { v =>
          assert(
            v.nonEmpty && !v.contains('\t') && !v.contains('\n'),
            s"${f.code}: bad field '$v'"
          )
        }
      assert(f.title.length <= 60, s"${f.code}: title too long")
      assert(
        f.explanation.endsWith("."),
        s"${f.code}: explanation must end with a period"
      )
      assert(
        f.doc.startsWith("#"),
        s"${f.code}: doc must be an index.md anchor"
      )
    }
  }
  test("byCode indexes every finding") {
    FindingCatalog.all.foreach(f =>
      assertEquals(FindingCatalog.get(f.code), Some(f))
    )
  }

  // Controller ruling (a): umbrella rules such as `TypelevelPurrism` re-prefix
  // the lint id a rule reports at runtime, so a harness that joins on the
  // reported category has only the *kind* to key on -- `rule` is not part of
  // what it sees. Two findings sharing a kind under different rule names would
  // therefore collide for such a harness even though their `code`s differ.
  test("kinds are globally unique across the catalog, not just codes") {
    val kinds = FindingCatalog.all.map(_.kind)
    val duplicates = kinds.diff(kinds.distinct).distinct
    assert(
      duplicates.isEmpty,
      s"kinds reused across findings: ${duplicates.mkString(", ")}"
    )
  }

  // Controller ruling (b): every `doc` anchor must exist as a heading in
  // docs/index.md, computed with GitHub's own anchor algorithm (lowercase,
  // spaces -> `-`, punctuation dropped) rather than trusted by construction.
  private def workspaceRoot: Path = {
    val testInput = Paths.get(
      sys.props.getOrElse("purrism.testInput", "scalafix/testInput/src")
    )
    // scalafix/testInput/src -> scalafix/testInput -> scalafix -> <root>
    testInput.getParent.getParent.getParent
  }

  private def githubAnchor(heading: String): String = {
    val stripped = heading.trim.toLowerCase.replaceAll("[^\\w\\s-]", "")
    stripped.trim.replaceAll("\\s+", "-")
  }

  private val headingPattern = """^#{1,6}\s+(.*)$""".r

  private def docAnchors: Set[String] = {
    val path = workspaceRoot.resolve("docs").resolve("index.md")
    Files
      .readAllLines(path)
      .stream()
      .toArray
      .toList
      .collect {
        case l: String if headingPattern.matches(l) =>
          val headingPattern(text) = l: @unchecked
          githubAnchor(text)
      }
      .toSet
  }

  test("every doc anchor exists as a heading in docs/index.md") {
    val anchors = docAnchors
    val missing = FindingCatalog.all
      .map(f => f.code -> f.doc.stripPrefix("#"))
      .filterNot { case (_, anchor) => anchors.contains(anchor) }
    assert(
      missing.isEmpty,
      s"doc anchors with no matching docs/index.md heading: ${missing.mkString(", ")}"
    )
  }

  test("unfixturable reasons are non-empty and keys are catalogued codes") {
    FindingCatalog.unfixturable.foreach { case (code, reason) =>
      assert(
        reason.trim.nonEmpty,
        s"$code: unfixturable reason must not be empty"
      )
      assert(
        FindingCatalog.byCode.contains(code),
        s"$code: listed in unfixturable but not in the catalog"
      )
    }
  }
}
