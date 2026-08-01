package fix.prefercats

import scala.meta._

/** Underscore lambdas and explicit type arguments are spellings, not structure:
  * each must normalize to the same IR as the longhand a different author would
  * write, or code using them is silently skipped by the matcher.
  */
class PlaceholderNormalizationSuite extends munit.FunSuite {

  private val members: Normalizer.MemberTable =
    Map(
      "f" -> "example/f().",
      "g" -> "example/g().",
      "empty" -> "example/empty."
    )

  private def ir(source: String, scope: List[String] = List("xs")): String =
    IR.canonical(Normalizer.normalize(source.parse[Term].get, scope, members))

  private def assertSameIr(placeholder: String, longhand: String): Unit =
    assertEquals(ir(placeholder), ir(longhand), s"$placeholder vs $longhand")

  test("`_.name` normalizes as `x => x.name`") {
    assertSameIr("xs.map(_.name)", "xs.map(x => x.name)")
  }

  test("`f(_)` normalizes as `x => f(x)`") {
    assertSameIr("xs.map(f(_))", "xs.map(x => f(x))")
  }

  test("two placeholders become two parameters, left to right") {
    assertSameIr("xs.fold(f(_, _))", "xs.fold((a, b) => f(a, b))")
  }

  test("a nested anonymous function keeps its own placeholder") {
    assertSameIr("xs.map(_.map(_.name))", "xs.map(a => a.map(b => b.name))")
  }

  test("explicit type arguments are erased") {
    assertSameIr("xs.map(g)", "xs.map(g)")
    assertEquals(ir("empty[Int]"), ir("empty"))
  }

  test("a placeholder lambda is distinguishable from a constant function") {
    assertNotEquals(ir("xs.map(_.name)"), ir("xs.map(x => f(x))"))
  }
}
