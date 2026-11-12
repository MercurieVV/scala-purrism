package fix.weakening

import munit.FunSuite
import scalafix.testkit.FixtureDocuments
import scalafix.v1.*

final class WeakeningSuite extends FunSuite {

  private given doc: SemanticDocument =
    FixtureDocuments("src/weakening/RequirementsFixture.scala")

  test("seeds hold only bounds of unary higher-kinded type parameters") {
    val seeds = Weakening.boundSymbols(doc.tree)
    assert(seeds.contains(Symbol("cats/effect/package.Sync#")), clue(seeds))
    assert(seeds.contains(Symbol("cats/package.MonadThrow#")), clue(seeds))
    assert(!seeds.exists(_.displayName == "Ordering"), clue(seeds))
    assert(!seeds.exists(_.displayName == "ClassTag"), clue(seeds))
  }
}
