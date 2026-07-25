package fix.hkt

import scala.meta.Defn
import scala.meta.Term
import scala.meta.XtensionCollectionLikeUI

import munit.FunSuite

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument
import scalafix.v1.Symbol
import scalafix.v1.XtensionTreeScalafix

final class UsageAnalyzerSuite extends FunSuite {
  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/hkt/UsageAnalyzerCases.scala")

  private lazy val index: CatsIndex = {
    val baseIndex = CatsIndex.load()
    val map = selectedMethod(findDef("mapOnly"))
    val reduce = selectedMethod(findDef("ambiguousCapability"))
    val functor = Symbol("cats/Functor#")
    val reducible = Symbol("cats/Reducible#")
    val semigroup = Symbol("cats/kernel/Semigroup#")
    val capabilities = List(
      functor -> Capability(
        functor,
        map,
        Symbol("cats/Functor#map()."),
        KindShape.Unary,
        derived = false,
        arity = 2
      ),
      reducible -> Capability(
        reducible,
        reduce,
        Symbol("cats/Reducible#reduceLeft()."),
        KindShape.Unary,
        derived = false,
        arity = 2
      ),
      semigroup -> Capability(
        semigroup,
        reduce,
        Symbol("cats/kernel/Semigroup#combine()."),
        KindShape.Star,
        derived = false,
        arity = 2
      )
    )
    new CatsIndex(
      baseIndex.typeclasses,
      capabilities.foldLeft(baseIndex.capabilities) {
        case (all, (typeclass, capability)) =>
          all.updated(typeclass, capability :: all.getOrElse(typeclass, Nil))
      },
      baseIndex.syntax
    )
  }

  test("List map requires Functor.map") {
    assertAbstractable("mapOnly", List(Symbol("cats/Functor#map().")))
  }

  test("List traverse requires Traverse.traverse") {
    assertAbstractable(
      "traverseOnly",
      List(Symbol("cats/Traverse#traverse()."))
    )
  }

  test("List foldMap requires Foldable.foldMap") {
    assertAbstractable("foldMapOnly", List(Symbol("cats/Foldable#foldMap().")))
  }

  test("List head declines as order-specific") {
    assertDecline("headOnly") {
      case _: DeclineReason.OrderOrIndexSpecific => true
      case _                                     => false
    }
  }

  test("Nil pattern declines as a concrete constructor match") {
    assertDecline("nilMatch") {
      case _: DeclineReason.ConcreteConstructorMatch => true
      case _                                         => false
    }
  }

  test("locally defined extension method declines without a capability") {
    assertDecline("missingCapability") {
      case _: DeclineReason.NoCapability => true
      case _                             => false
    }
  }

  test("List reduce declines with sorted ambiguous capability roots") {
    val result = oneResult("ambiguousCapability")
    result match {
      case UsageResult.Declined(_, DeclineReason.AmbiguousCapability(roots)) =>
        assert(
          roots == roots.sortBy(_.value) && roots.size >= 2,
          clues(result)
        )
      case other => fail(s"expected AmbiguousCapability, got ${render(other)}")
    }
  }

  test("Either declines as a Binary constructor") {
    assertDecline("binary") {
      case DeclineReason.UnsupportedKind(KindShape.Binary) => true
      case _                                               => false
    }
  }

  test("public map declines at the visibility boundary") {
    assertDecline("publicMap") {
      case DeclineReason.PublicBoundary("publicMap") => true
      case _                                         => false
    }
  }

  test("public head reports its body decline before the visibility boundary") {
    assertDecline("publicHead") {
      case _: DeclineReason.OrderOrIndexSpecific => true
      case _                                     => false
    }
  }

  test("public map is abstractable when public widening is enabled") {
    assertAbstractable(
      "publicMap",
      List(Symbol("cats/Functor#map().")),
      widenPublic = true
    )
  }

  test("mutable and throwing body declines as unsafe") {
    assertDecline("unsafeBody") {
      case _: DeclineReason.UnsafeBody => true
      case _                           => false
    }
  }

  private def assertAbstractable(
      name: String,
      expectedOps: List[Symbol],
      widenPublic: Boolean = false
  ): Unit = {
    val result = oneResult(name, widenPublic)
    result match {
      case UsageResult.Abstractable(_, _, _, _, ops) =>
        val actualOps = ops.map(_.method)
        assertEquals(actualOps, expectedOps, clues(result))
      case other => fail(s"expected Abstractable, got ${render(other)}")
    }
  }

  private def assertDecline(
      name: String
  )(expected: PartialFunction[DeclineReason, Boolean]): Unit = {
    oneResult(name) match {
      case result @ UsageResult.Declined(_, reason)
          if expected.applyOrElse(reason, (_: DeclineReason) => false) =>
        ()
      case result @ UsageResult.Declined(_, reason) =>
        fail(s"unexpected decline ${reason.message}: ${render(result)}")
      case other => fail(s"expected Declined, got ${render(other)}")
    }
  }

  private def oneResult(
      name: String,
      widenPublic: Boolean = false
  ): UsageResult = {
    val defn = findDef(name)
    val first = UsageAnalyzer.analyze(defn, index, widenPublic)
    val second = UsageAnalyzer.analyze(defn, index, widenPublic)
    assertEquals(
      second,
      first,
      s"non-deterministic $name: first=${renderAll(first)}, second=${renderAll(second)}"
    )
    first match {
      case result :: Nil => result
      case other =>
        fail(s"expected exactly one result for $name, got ${renderAll(other)}")
    }
  }

  private def findDef(name: String): Defn.Def =
    doc.tree
      .collect { case defn: Defn.Def if defn.name.value == name => defn }
      .headOption
      .getOrElse(fail(s"missing fixture definition: $name"))

  private def selectedMethod(defn: Defn.Def): Symbol =
    defn.body
      .collect { case Term.Select(_, method: Term.Name) => method.symbol }
      .headOption
      .getOrElse(fail(s"missing selected method in ${defn.name.value}"))

  private def clues(result: UsageResult): String =
    s"actual result: ${render(result)}"

  private def renderAll(results: List[UsageResult]): String =
    results.map(render).mkString("List(", ", ", ")")

  private def render(result: UsageResult): String =
    result match {
      case UsageResult.Abstractable(_, _, constructor, _, ops) =>
        s"Abstractable($constructor, ops=${ops.map(_.method).mkString("[", ", ", "]")})"
      case UsageResult.Declined(_, reason) =>
        s"Declined(${reason.message})"
    }
}
