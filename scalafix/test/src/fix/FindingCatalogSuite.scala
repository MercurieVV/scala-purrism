package fix

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
}
