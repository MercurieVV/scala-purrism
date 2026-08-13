package fix.flow

import munit.FunSuite

/** Driven by a hand-built graph: the traversal is the whole subject, and a
  * compiler in the loop would only obscure which edges were followed.
  */
final class ReachabilitySuite extends FunSuite {

  private def node(name: String): Node = Node(name, TypePath.root)

  private def edge(from: String, to: String): Edge =
    Edge(node(from), node(to), EdgeKind.ArgToParam, Provenance.unknown)

  test("forward follows the value and includes the seed") {
    val edges = List(edge("a", "b"), edge("b", "c"), edge("x", "y"))
    assertEquals(
      Reachability.forward(node("a"), edges),
      Set(node("a"), node("b"), node("c"))
    )
  }

  test("forward does not walk backwards") {
    assertEquals(
      Reachability.forward(node("b"), List(edge("a", "b"))),
      Set(node("b"))
    )
  }

  test("forward terminates on a cycle") {
    val edges = List(edge("a", "b"), edge("b", "a"))
    assertEquals(
      Reachability.forward(node("a"), edges),
      Set(node("a"), node("b"))
    )
  }

  test("firstStop names the node that rejected, not merely that one did") {
    val edges = List(edge("a", "b"), edge("b", "c"))
    assertEquals(
      Reachability.firstStop(node("a"), edges, _ == node("c")),
      Some(node("c"))
    )
  }

  test("firstStop ignores the seed itself") {
    assertEquals(
      Reachability.firstStop(node("a"), List(edge("a", "b")), _ => true),
      Some(node("b"))
    )
  }

  test("firstStop is None when nothing on the path is rejected") {
    assertEquals(
      Reachability.firstStop(node("a"), List(edge("a", "b")), _ => false),
      None
    )
  }
}
