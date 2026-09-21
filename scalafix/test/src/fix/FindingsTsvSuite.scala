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
}
