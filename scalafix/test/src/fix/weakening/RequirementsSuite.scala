package fix.weakening

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.duration.*
import scala.meta.*

import fix.hkt.CatsIndex
import fix.hkt.tasty.CacheConfig
import fix.hkt.tasty.RuntimeIndex
import munit.FunSuite
import scalafix.testkit.FixtureDocuments
import scalafix.v1.*

final class RequirementsSuite extends FunSuite {

  override val munitTimeout: Duration = 10.minutes

  private given doc: SemanticDocument =
    FixtureDocuments("src/weakening/RequirementsFixture.scala")

  private def s(name: String): Symbol = Symbol(s"cats/$name#")

  private val Sync = Symbol("cats/effect/kernel/Sync#")

  private lazy val classpath: List[Path] =
    System
      .getProperty("java.class.path")
      .split(java.io.File.pathSeparator)
      .toList
      .filter(_.nonEmpty)
      .map(Paths.get(_))

  private lazy val index: CatsIndex =
    RuntimeIndex
      .load(
        classpath,
        Set(Sync, Symbol("cats/effect/package.Sync#")),
        CacheConfig(
          Some(Files.createTempDirectory("requirements-suite")),
          false
        )
      )
      .fold(fail(_), identity)

  private lazy val allSites: List[BoundSite] =
    Requirements.sites(doc.tree, index)

  private def site(owner: String): BoundSite =
    allSites
      .find(site => ownerName(site.owner).contains(owner))
      .getOrElse(
        fail(
          s"no bound site owned by $owner; got ${allSites
              .map(s => ownerName(s.owner))}"
        )
      )

  private def ownerName(tree: Tree): Option[String] = tree match {
    case d: Defn.Def   => Some(d.name.value)
    case c: Defn.Class => Some(c.name.value)
    case t: Defn.Trait => Some(t.name.value)
    case _             => None
  }

  private def noHelpers(symbol: Symbol): Option[Symbol] = None

  private def collect(
      owner: String,
      helpers: Symbol => Option[Symbol] = noHelpers
  ): Option[Set[Symbol]] =
    Requirements.collect(site(owner), index, helpers)

  test("sites: context bounds resolve to their typeclass symbol") {
    val mapOnly = site("mapOnly")
    assertEquals(mapOnly.tparam.name.value, "F")
    assertEquals(mapOnly.boundSymbol, Sync)
    assertEquals(mapOnly.boundFixedArgs, Nil)
  }

  test("sites: an alias bound resolves to its target and fixed args") {
    val alias = site("aliasBound")
    assertEquals(alias.boundSymbol, s("MonadError"))
    assertEquals(alias.boundFixedArgs, List("java/lang/Throwable#"))
  }

  test("sites: the class and the shadowing method are separate sites") {
    val shadow = site("Shadow")
    val inner = site("inner")
    assertNotEquals(shadow.tparam.name.symbol, inner.tparam.name.symbol)
  }

  test("map needs Functor") {
    assertEquals(collect("mapOnly"), Some(Set(s("Functor"))))
  }

  test("map, product and pure need Functor, Semigroupal and Applicative") {
    assertEquals(
      collect("mapProductPure"),
      Some(Set(s("Functor"), s("Semigroupal"), s("Applicative")))
    )
  }

  test("xs.traverse(f) needs Applicative of the effect, not Traverse") {
    assertEquals(collect("traverseList"), Some(Set(s("Applicative"))))
  }

  test("flatMap needs FlatMap") {
    assertEquals(collect("flatMapOnly"), Some(Set(s("FlatMap"))))
  }

  test("for-comprehension: its conversions explain flatMap and map") {
    assertEquals(
      collect("comprehension"),
      Some(Set(s("FlatMap"), s("Functor")))
    )
  }

  test("local helper contributes its weakened bound") {
    val helper = site("helper").owner.symbol
    assertEquals(
      collect("usesHelper", sym => Option.when(sym == helper)(s("Functor"))),
      Some(Set(s("Functor")))
    )
  }

  test("local helper without a weakened bound contributes its signature") {
    assertEquals(collect("usesHelper"), Some(Set(Sync)))
  }

  test("an unexplained external call keeps the bound") {
    assertEquals(collect("external"), None)
  }

  test("alias bound collects against its target typeclass") {
    assertEquals(collect("aliasBound"), Some(Set(s("Functor"))))
  }

  test("shadowed type parameter: inner evidence never counts for outer") {
    assertEquals(collect("Shadow"), Some(Set(s("Functor"))))
    assertEquals(collect("inner"), None)
  }

  test("isMethod distinguishes methods from values") {
    assert(Requirements.isMethod(Symbol("cats/Traverse.Ops#traverse().")))
    assert(!Requirements.isMethod(Symbol("weakening/X.f().(fa)")))
    assert(!Requirements.isMethod(Symbol("local3")))
  }

}
