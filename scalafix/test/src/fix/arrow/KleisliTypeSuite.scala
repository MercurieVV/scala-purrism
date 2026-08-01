package fix.arrow

import scala.meta._

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument

/** `KleisliType` against real compiler-emitted SemanticDB, using the shapes in
  * `golden/ArrowKleisliTypes.scala` -- each of which mirrors a declaration in
  * the reference corpus `gh-tasks-llm-executor`.
  *
  * The rejection cases matter as much as the acceptance ones: `-->` is both a
  * Kleisli alias and an abstract type parameter in that corpus, so a name-based
  * check would fuse them.
  */
class KleisliTypeSuite extends munit.FunSuite {

  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/golden/ArrowKleisliTypes.scala")

  /** The declared value of a `def`/`val` by name, as a term to ask about. For a
    * `def` this is a `Term.Name` occurrence in the tree, which is what the rule
    * itself sees at a call site.
    */
  private def declarationNamed(name: String): Term.Name = {
    // Collected as two flat lists rather than a nested one: scalameta defines
    // an implicit `List[Stat] => Term.Block`, which hijacks the inference of a
    // `List[List[Term.Name]]` and makes the flattened result typecheck as a
    // `Term.Block`.
    val defs: List[Term.Name] = doc.tree.collect {
      case defn: Defn.Def if defn.name.value == name => defn.name
    }
    val vals: List[Term.Name] = doc.tree.collect {
      case Pat.Var(varName) if varName.value == name => varName
    }
    (defs ++ vals).headOption
      .getOrElse(sys.error(s"no declaration named '$name' in the fixture"))
  }

  private def assertKleisli(name: String): Unit =
    assert(
      KleisliType.isKleisli(declarationNamed(name)),
      s"'$name' should resolve to cats.data.Kleisli"
    )

  private def assertNotKleisli(name: String): Unit =
    assert(
      !KleisliType.isKleisli(declarationNamed(name)),
      s"'$name' must not resolve to cats.data.Kleisli"
    )

  test("resolves a bare Kleisli type") {
    assertKleisli("viaBare")
  }

  test("resolves through a type alias") {
    assertKleisli("viaAlias")
  }

  test("resolves through a type-lambda alias") {
    assertKleisli("viaFlow")
  }

  test("resolves a fully qualified Kleisli") {
    assertKleisli("viaFullyQualified")
  }

  test("resolves an inferred val with no declared type") {
    assertKleisli("inferred")
  }

  test("resolves a method whose return type is a Kleisli") {
    assertKleisli("parameterised")
  }

  test("rejects a plain method named run") {
    assertNotKleisli("run")
  }

  test("rejects a local type that merely exposes a run member") {
    // `Pipe(run = ...)` -- the field is named `run` but the enclosing type is
    // not a Kleisli, the shape `ArrowFlowNonKleisliRun.scala` pins.
    assertNotKleisli("pipeline")
  }

  test("rejects an abstract type parameter spelled like the Kleisli alias") {
    // `ProgramArrows[-->[_, _]]` uses the same token as the `-->` Kleisli
    // alias. SemanticDB distinguishes them: an alias carries its right-hand
    // side as both bounds, an abstract type parameter does not.
    assertNotKleisli("firstArrow")
  }

  test("recognises all three Kleisli constructor spellings") {
    val applies = doc.tree.collect {
      case applyTerm: Term.Apply if KleisliType.isKleisliApply(applyTerm) =>
        applyTerm
    }
    // `bare`, `explicit` and `ascribed` in the fixture's `Applies` class, plus
    // the constructor calls in every Kleisli-returning declaration above.
    assert(
      applies.length >= 3,
      s"expected at least the three Applies spellings, got ${applies.length}"
    )
  }
}
