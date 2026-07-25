package fix.hkt

import scala.meta._

import munit.FunSuite

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument
import scalafix.v1.Symbol
import scalafix.v1.XtensionTreeScalafix

final class UsageAnalyzerSuite extends FunSuite {
  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/golden/UsageAnalyzerCases.scala")

  private lazy val baseIndex: CatsIndex = CatsIndex.load()

  private lazy val index: CatsIndex = {
    val map = selectedMethod(definition("mapOnly"))
    val functor = Symbol("cats/Functor#")
    val capability = Capability(
      functor,
      map,
      Symbol("cats/Functor#map()."),
      KindShape.Unary,
      derived = false,
      arity = 2
    )
    new CatsIndex(
      baseIndex.typeclasses,
      baseIndex.capabilities.updated(
        functor,
        capability :: baseIndex.capabilities.getOrElse(functor, Nil)
      ),
      baseIndex.syntax
    )
  }

  test("map-only body requires the Functor map override root") {
    assertEquals(
      requiredMethods("mapOnly"),
      Set(Symbol("cats/Functor#map()."))
    )
  }

  test("traverse body requires Traverse.traverse") {
    assertEquals(
      requiredMethods("traverseOnly"),
      Set(Symbol("cats/Traverse#traverse()."))
    )
  }

  test("foldMap body requires Foldable.foldMap") {
    assertEquals(
      requiredMethods("foldMapOnly"),
      Set(Symbol("cats/Foldable#foldMap()."))
    )
  }

  test("List.head declines as order-specific") {
    decline("headOnly").reason match {
      case _: DeclineReason.OrderOrIndexSpecific => ()
      case other => fail(s"expected OrderOrIndexSpecific, got $other")
    }
  }

  test("a Nil pattern declines as a concrete-constructor match") {
    decline("nilMatch").reason match {
      case _: DeclineReason.ConcreteConstructorMatch => ()
      case other => fail(s"expected ConcreteConstructorMatch, got $other")
    }
  }

  test("a resolved operation absent from every index declines") {
    decline("missingCapability").reason match {
      case _: DeclineReason.NoCapability => ()
      case other => fail(s"expected NoCapability, got $other")
    }
  }

  test("unrelated capability roots decline as ambiguous") {
    val reduce = selectedMethod(definition("ambiguousCapability"))
    val reducible = Capability(
      Symbol("cats/Reducible#"),
      reduce,
      Symbol("cats/Reducible#reduceLeft()."),
      KindShape.Unary,
      derived = false,
      arity = 2
    )
    val semigroup = Capability(
      Symbol("cats/kernel/Semigroup#"),
      reduce,
      Symbol("cats/kernel/Semigroup#combine()."),
      KindShape.Star,
      derived = false,
      arity = 2
    )
    val ambiguousIndex = new CatsIndex(
      index.typeclasses,
      index.capabilities
        .updated(Symbol("cats/Reducible#"), List(reducible))
        .updated(Symbol("cats/kernel/Semigroup#"), List(semigroup)),
      index.syntax
    )
    val result =
      UsageAnalyzer.analyze(
        definition("ambiguousCapability"),
        ambiguousIndex,
        widenPublic = false
      )
    result.collectFirst { case declined: UsageResult.Declined => declined.reason } match {
      case Some(_: DeclineReason.AmbiguousCapability) => ()
      case other => fail(s"expected AmbiguousCapability, got $other")
    }
  }

  test("List.apply indexing declines as order-specific") {
    decline("indexed").reason match {
      case _: DeclineReason.OrderOrIndexSpecific => ()
      case other => fail(s"expected OrderOrIndexSpecific, got $other")
    }
  }

  test("unsafe casts decline as unsafe bodies") {
    decline("unsafeCast").reason match {
      case _: DeclineReason.UnsafeBody => ()
      case other => fail(s"expected UnsafeBody, got $other")
    }
  }

  test("Either in an abstractable position declines as Binary") {
    assertEquals(
      decline("binary").reason,
      DeclineReason.UnsupportedKind(KindShape.Binary)
    )
  }

  test("type lambdas decline as unsupported Binary constructors") {
    assertEquals(
      decline("typeLambda").reason,
      DeclineReason.UnsupportedKind(KindShape.Binary)
    )
  }

  test("isWidenable rejects bare protected") {
    assert(!UsageAnalyzer.isWidenable(definition("bareProtected"), widenPublic = false))
  }

  test("isWidenable accepts package-private") {
    assert(UsageAnalyzer.isWidenable(definition("packagePrivate"), widenPublic = false))
  }

  test("isWidenable accepts a local def") {
    assert(UsageAnalyzer.isWidenable(definition("localDefinition"), widenPublic = false))
  }

  test("isWidenable accepts public when widenPublic is enabled") {
    assert(UsageAnalyzer.isWidenable(definition("publicMap"), widenPublic = true))
  }

  test("a public head reports the body decline, not PublicBoundary") {
    decline("publicHead").reason match {
      case _: DeclineReason.OrderOrIndexSpecific => ()
      case other => fail(s"expected OrderOrIndexSpecific, got $other")
    }
  }

  test("analysis is deterministic") {
    val defn = definition("mapOnly")
    assertEquals(
      UsageAnalyzer.analyze(defn, index, widenPublic = false),
      UsageAnalyzer.analyze(defn, index, widenPublic = false)
    )
  }

  test("all decline messages are distinct one-line diagnostics") {
    val reasons: List[DeclineReason] = List(
      DeclineReason.ConcreteConstructorMatch("Nil"),
      DeclineReason.OrderOrIndexSpecific("head"),
      DeclineReason.UnsupportedKind(KindShape.Binary),
      DeclineReason.PublicBoundary("example"),
      DeclineReason.AmbiguousCapability(
        List(Symbol("cats/Functor#map()."), Symbol("cats/Foldable#foldMap()."))
      ),
      DeclineReason.NoCapability(Symbol("example/Missing#operation().")),
      DeclineReason.UnsafeBody("throw"),
      DeclineReason.NameConflict(List("F", "G")),
      DeclineReason.TooManyConstraints(List(Symbol("cats/Functor#")), 0),
      DeclineReason.MissingEvidence
    )
    assertEquals(reasons.map(_.message).distinct.size, reasons.size)
    assert(reasons.forall(reason => !reason.message.contains("\n")))
  }

  private def requiredMethods(name: String): Set[Symbol] =
    UsageAnalyzer
      .analyze(definition(name), index, widenPublic = false)
      .collect { case UsageResult.Abstractable(_, _, _, _, ops) => ops }
      .flatten
      .map(_.method)
      .toSet

  private def decline(name: String): UsageResult.Declined =
    UsageAnalyzer
      .analyze(definition(name), index, widenPublic = false)
      .collectFirst { case declined: UsageResult.Declined => declined }
      .getOrElse(fail(s"$name did not decline"))

  private def definition(name: String): Defn.Def =
    doc.tree.collect {
      case defn: Defn.Def if defn.name.value == name => defn
    }.headOption.getOrElse(fail(s"missing fixture definition: $name"))

  private def selectedMethod(defn: Defn.Def): Symbol =
    defn.body.collect {
      case Term.Select(_, method: Term.Name) => method.symbol
    }.headOption.getOrElse(fail(s"missing selected method in ${defn.name.value}"))
}
