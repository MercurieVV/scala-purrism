package fix.vocabulary

import munit.FunSuite

class PatternListSuite extends FunSuite {
  test("exact FQCN entry matches itself") {
    val pl = PatternList.compile(List("cats.arrow.Arrow")).toOption.get
    assert(pl.matches("cats.arrow.Arrow"))
    assert(!pl.matches("cats.arrow.Compose"))
  }

  test("regex entry matches a whole subpackage") {
    val pl = PatternList.compile(List("cats\\.arrow\\..*")).toOption.get
    assert(pl.matches("cats.arrow.Arrow"))
    assert(pl.matches("cats.arrow.Compose"))
    assert(!pl.matches("cats.data.Kleisli"))
  }

  test("invalid regex is reported, not thrown") {
    val result = PatternList.compile(List("cats.arrow.(["))
    assert(result.isLeft)
  }

  test("normalize turns a SemanticDB symbol into a dotted FQCN") {
    assertEquals(PatternList.normalize("cats/arrow/Arrow#"), "cats.arrow.Arrow")
    assertEquals(
      PatternList.normalize("com/foo/wiring/Pipeline#make()."),
      "com.foo.wiring.Pipeline.make"
    )
  }
}
