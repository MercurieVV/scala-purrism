package fix.vocabulary

import munit.FunSuite
import scala.meta._
import scala.meta.dialects.Scala3

class ConstructMatcherSuite extends FunSuite {
  test("matches a Term.If node by exact class name") {
    val matcher =
      ConstructMatcher.compile(List("scala.meta.Term.If")).toOption.get
    val tree = "if (true) 1 else 2".parse[Term].get
    assert(matcher.matches(tree))
  }

  test("does not match an unrelated node") {
    val matcher =
      ConstructMatcher.compile(List("scala.meta.Term.If")).toOption.get
    val tree = "1 + 1".parse[Term].get
    assert(!matcher.matches(tree))
  }

  test("unknown class name is reported, not thrown") {
    assert(ConstructMatcher.compile(List("not.a.real.Class")).isLeft)
  }

  test("findAll locates every banned node in a tree") {
    val matcher =
      ConstructMatcher.compile(List("scala.meta.Defn.Var")).toOption.get
    val tree =
      """
        |class A {
        |  var x = 1
        |  var y = 2
        |  val z = 3
        |}
        |""".stripMargin.parse[Source].get
    assertEquals(matcher.findAll(tree).size, 2)
  }
}
