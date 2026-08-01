package fix.prefercats

import scala.meta._

/** [[BlockInliner]] decides whether a block may be compared as one expression.
  * Its whole safety argument is the use count, so that is what these pin.
  */
class BlockInlinerSuite extends munit.FunSuite {

  private def inlined(source: String): String =
    BlockInliner.inlineLets(source.parse[Term].get).syntax

  test("a single-use val is substituted into its use site") {
    assertEquals(
      inlined("{ val t = xs.traverse(f); t.map(g) }"),
      "xs.traverse(f).map(g)"
    )
  }

  test("a val used as the whole result collapses to its right-hand side") {
    assertEquals(inlined("{ val t = xs.traverse(f); t }"), "xs.traverse(f)")
  }

  test("a val used twice keeps its binding: inlining would evaluate twice") {
    val block = "{ val t = xs.traverse(f); combine(t, t) }"
    assertEquals(
      inlined(block),
      Term
        .Block(block.parse[Term].get.children.collect { case s: Stat =>
          s
        })
        .syntax
    )
  }

  test("an unused val keeps its binding: inlining would drop an effect") {
    assertEquals(
      inlined("{ val t = launchMissiles(); done }").contains("val t"),
      true
    )
  }

  test("a shadowed reference does not count as a use") {
    // The inner `t` belongs to the lambda, so the outer `t` is used zero times
    // and must stay put.
    assertEquals(
      inlined("{ val t = xs.traverse(f); ys.map(t => t + 1) }").contains(
        "val t"
      ),
      true
    )
  }

  test("nested single-use vals collapse from the inside out") {
    assertEquals(
      inlined("{ val a = xs.traverse(f); val b = a.map(g); b.void }"),
      "xs.traverse(f).map(g).void"
    )
  }

  test("a non-val statement stops inlining") {
    assertEquals(
      inlined("{ println(1); val t = xs.traverse(f); t.map(g) }").startsWith(
        "{"
      ),
      true
    )
  }
}
