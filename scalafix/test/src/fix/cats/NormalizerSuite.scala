package fix.prefercats

import scala.meta._

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument

/** `Normalizer.normalize` against real compiler-emitted SemanticDB, using
  * `golden/PreferCatsNormalizerFixtures.scala` -- one method body per row of
  * docs/PREFER_CATS_FUNCTIONS.md's erasure (§1) / preservation (§2) tables.
  */
class NormalizerSuite extends munit.FunSuite {

  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/golden/PreferCatsNormalizerFixtures.scala")

  private def defnOf(name: String): Defn.Def =
    doc.tree
      .collect { case d: Defn.Def if d.name.value == name => d }
      .headOption
      .getOrElse(sys.error(s"no def named '$name' in the fixture"))

  private def bodyOf(name: String): Term = defnOf(name).body

  /** Seeds the def's own value-parameter names as the initial scope, so
    * references to them resolve to `Bound` slots rather than as free names that
    * happen to differ per-method (which would defeat alpha-renaming equality
    * across differently-named methods).
    */
  private def normalized(name: String): IR = {
    val defn = defnOf(name)
    val paramNames =
      defn.paramClauses.flatMap(_.values.map(_.name.value)).toList
    Normalizer.normalize(defn.body, paramNames)
  }

  test("alpha-renamed bodies normalize equal") {
    assertEquals(normalized("sumA"), normalized("sumB"))
  }

  test("for vs flatMap/map normalize equal") {
    assertEquals(normalized("viaFor"), normalized("viaChain"))
  }

  test("eta-expanded vs direct application normalize equal") {
    assertEquals(normalized("usesEta"), normalized("usesDirect"))
  }

  test("differing evaluation order normalizes unequal") {
    assertNotEquals(normalized("orderAB"), normalized("orderBA"))
  }

  test("differing evaluation count normalizes unequal") {
    assertNotEquals(normalized("evalOnce"), normalized("evalTwice"))
  }

  test("differing by-name-ness normalizes unequal") {
    assertNotEquals(normalized("usesByName"), normalized("usesStrict"))
  }

  test("by-name parameter is reflected as a byName flag in the IR") {
    normalized("usesByName") match {
      case IR.App(_, _, byName) =>
        assertEquals(byName, List(false, true))
      case other =>
        fail(s"expected an App, got $other")
    }
  }

  test("strict parameter is reflected as a non-byName flag in the IR") {
    normalized("usesStrict") match {
      case IR.App(_, _, byName) =>
        assertEquals(byName, List(false, false))
      case other =>
        fail(s"expected an App, got $other")
    }
  }

  test("normalization is deterministic across runs") {
    val first = normalized("viaFor")
    val second = normalized("viaFor")
    assertEquals(first, second)
    assertEquals(IR.hash(first), IR.hash(second))
    assertEquals(IR.canonical(first), IR.canonical(second))
  }

  test("mutation prevents normalization from silently erasing it") {
    // hasMutation is unusable per CandidateExtractor; Normalizer itself has
    // no representation for `var`/assignment, so it must fail loudly rather
    // than pretend the body is pure.
    intercept[Normalizer.UnsupportedShape] {
      Normalizer.normalize(bodyOf("hasMutation"), Nil)
    }
  }
}
