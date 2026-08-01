package fix.arrow

import scala.meta._

import fix.arrow.ArrowIR._

/** The pure half of the engine -- normalize and render -- exercised directly on
  * hand-built `ArrowIR`, with no SemanticDB in the loop. Covers the two
  * guarantees the rule leans on: normalization reaches a fixpoint (which is
  * what makes the rule idempotent), and rendering respects operator precedence.
  */
class ArrowEngineSuite extends munit.FunSuite {

  private def eff(name: String): Eff = Eff(Term.Name(name))
  private val f = Term.Name("f")
  private val g = Term.Name("g")

  test("normalize is idempotent") {
    val samples = List[ArrowIR](
      AndThen(AndThen(eff("a"), eff("b")), eff("c")),
      AndThen(Id, eff("a")),
      AndThen(eff("a"), Id),
      AndThen(eff("a"), ArrowIR.Lift(f)),
      AndThen(ArrowIR.Lift(f), eff("a")),
      Merge(eff("a"), Merge(eff("b"), eff("c"))),
      ProductL(ProductL(eff("a"), eff("b")), eff("c")),
      Rmap(AndThen(eff("a"), eff("b")), f),
      Choice(eff("a"), eff("b")),
      Local(f, AndThen(eff("a"), eff("b")))
    )
    samples.foreach { ir =>
      val once = ArrowNormalize(ir)
      val twice = ArrowNormalize(once)
      assertEquals(twice, once, s"normalize not idempotent on $ir")
    }
  }

  test("andThen chains right-associate and flatten") {
    val ir = AndThen(AndThen(eff("a"), eff("b")), eff("c"))
    assertEquals(ArrowRender.render(ArrowNormalize(ir)), "a >>> b >>> c")
  }

  test("a trailing pure step becomes a map") {
    val ir = AndThen(AndThen(eff("a"), eff("b")), ArrowIR.Lift(f))
    assertEquals(ArrowRender.render(ArrowNormalize(ir)), "a >>> b.map(f)")
  }

  test("a leading pure step becomes a local") {
    val ir = AndThen(ArrowIR.Lift(f), eff("a"))
    assertEquals(ArrowRender.render(ArrowNormalize(ir)), "a.local(f)")
  }

  test("productL chains right-associate and flatten") {
    val a = eff("a")
    val b = eff("b")
    val c = eff("c")
    val ir = ProductL(ProductL(a, b), c)
    val normalized = ProductL(a, ProductL(b, c))
    assertEquals(ArrowNormalize(ir), normalized)
    assertEquals(ArrowRender.render(normalized), "a <* b <* c")
  }

  test("productL removes only a trailing identity") {
    val a = eff("a")
    assertEquals(ArrowNormalize(ProductL(a, Id)), a)
    assertEquals(ArrowNormalize(ProductL(Id, a)), ProductL(Id, a))
  }

  test("productL renders typed ask and parenthesises a composed operand") {
    val ir =
      ProductL(Ask("F", "Task"), AndThen(eff("validate"), eff("record")))
    assertEquals(
      ArrowRender.render(ir),
      "Kleisli.ask[F, Task] <* (validate >>> record)"
    )
  }

  test("mixed product operands are parenthesised") {
    assertEquals(
      ArrowRender.render(ProductR(eff("a"), ProductL(eff("b"), eff("c")))),
      "a *> (b <* c)"
    )
    assertEquals(
      ArrowRender.render(ProductL(eff("a"), ProductR(eff("b"), eff("c")))),
      "a <* (b *> c)"
    )
  }

  test("merge chains flatten and are left-associative") {
    val ir = Merge(Merge(eff("a"), eff("b")), eff("c"))
    assertEquals(ArrowRender.render(ir), "a &&& b &&& c")
  }

  test("a merge operand of a chain is parenthesised") {
    val ir = AndThen(Merge(eff("a"), eff("b")), eff("c"))
    assertEquals(ArrowRender.render(ir), "(a &&& b) >>> c")
  }

  test("a chain receiver of a map is parenthesised") {
    val ir = Rmap(AndThen(eff("a"), eff("b")), f)
    assertEquals(ArrowRender.render(ir), "(a >>> b).map(f)")
  }

  test("a choice operand of a chain is parenthesised") {
    val ir = AndThen(eff("a"), Choice(eff("b"), eff("c")))
    assertEquals(ArrowRender.render(ir), "a >>> (b ||| c)")
  }

  test("rendering an Opaque leaf fails loudly") {
    intercept[RuntimeException] {
      ArrowRender.render(Opaque(Term.Name("mystery")))
    }
  }

  test("effect count and opaque detection fold the whole tree") {
    val ir = AndThen(eff("a"), Merge(eff("b"), Opaque(Term.Name("x"))))
    assertEquals(ArrowIR.effectCount(ir), 2)
    assert(ArrowIR.containsOpaque(ir))
  }

  test("readability budget sees an Opaque nested inside ProductL") {
    val ir = ProductL(eff("work"), Opaque(Term.Name("mystery")))
    assertEquals(ArrowIR.effectCount(ir), 1)
    assertEquals(
      ReadabilityBudget.verdict(ir, renderedLength = 0, sourceLength = 100),
      ReadabilityBudget.Decline(
        "body contains a subexpression the rule could not analyse"
      )
    )
  }
}
