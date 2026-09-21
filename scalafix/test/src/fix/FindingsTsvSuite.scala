package fix

final class FindingsTsvSuite extends munit.FunSuite {
  test("render/parse round-trip and header") {
    val text = FindingsTsv.render("0.9.1", FindingCatalog.all)
    assert(text.startsWith("# purrism 0.9.1\n"))
    assertEquals(FindingsTsv.parse(text), Right(("0.9.1", FindingCatalog.all)))
    assertEquals(
      text.linesIterator.drop(1).toList.map(_.split('\t').length).distinct,
      List(6)
    )
  }

  test("parse rejects a code that does not start with its rule") {
    val text =
      "# purrism 0.9.1\nOther.manual-resource\tPreferEffectIdioms\tt\te.\ti\t#x\n"
    assertEquals(
      FindingsTsv.parse(text),
      Left("code Other.manual-resource does not start with PreferEffectIdioms.")
    )
  }
  test("parse rejects a kind that is not kebab-case") {
    val text =
      "# purrism 0.9.1\nPreferEffectIdioms.Manual_Resource\tPreferEffectIdioms\tt\te.\ti\t#x\n"
    assertEquals(
      FindingsTsv.parse(text),
      Left(
        "code PreferEffectIdioms.Manual_Resource: kind 'Manual_Resource' is not kebab-case"
      )
    )
  }
}
