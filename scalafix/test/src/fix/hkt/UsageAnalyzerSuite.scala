package fix.hkt

import scala.meta._

import munit.FunSuite

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument
import scalafix.v1.Symbol
import scalafix.v1.XtensionTreeScalafix

final class UsageAnalyzerSuite extends FunSuite {
  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/golden/HktUsageAnalysis.scala")

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
      baseIndex.syntax,
      baseIndex.stdlib
    )
  }

  test("map-only body requires the Functor map override root") {
    val ops = abstractable("mapOnly").ops
    val position = ops.headOption
      .map(_.position)
      .getOrElse(fail("mapOnly produced no operations"))
    assertEquals(
      ops,
      List(RequiredOp(Symbol("cats/Functor#map()."), position, KindShape.Unary))
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

  test("a cons pattern declines as a concrete-constructor match") {
    decline("consMatch").reason match {
      case DeclineReason.ConcreteConstructorMatch(what) =>
        assertEquals(what, "::")
      case other => fail(s"expected ConcreteConstructorMatch, got $other")
    }
  }

  test(
    "an Option constructor pattern declines as a concrete-constructor match"
  ) {
    decline("someMatch").reason match {
      case DeclineReason.ConcreteConstructorMatch(what) =>
        assertEquals(what, "Some")
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
      index.syntax,
      index.stdlib
    )
    val result =
      UsageAnalyzer.analyze(
        definition("ambiguousCapability"),
        ambiguousIndex,
        widenPublic = false
      )
    result.collectFirst { case declined: UsageResult.Declined =>
      declined.reason
    } match {
      case Some(DeclineReason.AmbiguousCapability(roots)) =>
        assertEquals(
          roots.toSet,
          Set(
            Symbol("cats/Reducible#reduceLeft()."),
            Symbol("cats/kernel/Semigroup#combine().")
          )
        )
      case other => fail(s"expected AmbiguousCapability, got $other")
    }
  }

  test("reviewed stdlib ambiguity declines without a test-only index entry") {
    val results =
      UsageAnalyzer.analyze(
        definition("ambiguousCapability"),
        baseIndex,
        widenPublic = false
      )
    results.collectFirst { case declined: UsageResult.Declined =>
      declined.reason
    } match {
      case Some(DeclineReason.AmbiguousCapability(candidates)) =>
        assertEquals(
          candidates,
          List(
            Symbol("cats/Reducible#reduceLeft()."),
            Symbol("cats/kernel/Semigroup#combine().")
          )
        )
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

  test("unsafe effect execution declines as an unsafe body") {
    decline("unsafeEffect").reason match {
      case DeclineReason.UnsafeBody(what) =>
        assertEquals(what, "unsafeRunSync")
      case other => fail(s"expected UnsafeBody, got $other")
    }
  }

  test("mutable variable definitions decline as unsafe bodies") {
    decline("mutableVariable").reason match {
      case _: DeclineReason.UnsafeBody => ()
      case other => fail(s"expected UnsafeBody, got $other")
    }
  }

  test("assignment to an enclosing var declines as an unsafe body") {
    decline("mutableAssignment").reason match {
      case _: DeclineReason.UnsafeBody => ()
      case other => fail(s"expected UnsafeBody, got $other")
    }
  }

  test("a named argument is not treated as mutable assignment") {
    val results =
      UsageAnalyzer.analyze(
        definition("namedArgument"),
        index,
        widenPublic = false
      )
    assert(!results.exists {
      case UsageResult.Declined(_, _: DeclineReason.UnsafeBody) => true
      case _                                                    => false
    })
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

  test("the kind gate precedes unsafe-body checks") {
    assertEquals(
      decline("binaryUnsafe").reason,
      DeclineReason.UnsupportedKind(KindShape.Binary)
    )
  }

  test("isWidenable rejects bare protected") {
    assert(
      !UsageAnalyzer.isWidenable(
        definition("bareProtected"),
        widenPublic = false
      )
    )
  }

  test("isWidenable accepts package-private") {
    assert(
      UsageAnalyzer.isWidenable(
        definition("packagePrivate"),
        widenPublic = false
      )
    )
  }

  test("isWidenable accepts a local def") {
    assert(
      UsageAnalyzer.isWidenable(
        definition("localDefinition"),
        widenPublic = false
      )
    )
  }

  test("isWidenable accepts public when widenPublic is enabled") {
    assert(
      UsageAnalyzer.isWidenable(definition("publicMap"), widenPublic = true)
    )
  }

  test("isWidenable accepts a restricted owner chain") {
    assert(
      UsageAnalyzer.isWidenable(
        definition("restrictedOwner"),
        widenPublic = false
      )
    )
  }

  test("a public head reports the body decline, not PublicBoundary") {
    val results = UsageAnalyzer.analyze(
      definition("publicHead"),
      index,
      widenPublic = false
    )
    assertEquals(results.size, 1)
    results.head match {
      case declined: UsageResult.Declined =>
        declined.reason match {
          case _: DeclineReason.OrderOrIndexSpecific => ()
          case other => fail(s"expected OrderOrIndexSpecific, got $other")
        }
      case other => fail(s"expected Declined, got $other")
    }
  }

  test("a public, otherwise-abstractable def declines as a public boundary") {
    decline("publicMap", widenPublic = false).reason match {
      case DeclineReason.PublicBoundary(name) => assertEquals(name, "publicMap")
      case other => fail(s"expected PublicBoundary, got $other")
    }
  }

  test("the same public def is abstractable when widenPublic is enabled") {
    val results =
      UsageAnalyzer.analyze(definition("publicMap"), index, widenPublic = true)
    assert(results.forall(_.isInstanceOf[UsageResult.Abstractable]), results)
  }

  test("a bare protected def declines as a public boundary") {
    decline("bareProtected", widenPublic = false).reason match {
      case DeclineReason.PublicBoundary(name) =>
        assertEquals(name, "bareProtected")
      case other => fail(s"expected PublicBoundary, got $other")
    }
  }

  test("a private[golden] def is abstractable") {
    val results = UsageAnalyzer.analyze(
      definition("packagePrivate"),
      index,
      widenPublic = false
    )
    assert(results.forall(_.isInstanceOf[UsageResult.Abstractable]), results)
  }

  test(
    "a def mentioning two concrete constructors yields two independent results"
  ) {
    val results =
      UsageAnalyzer.analyze(
        definition("twoConstructors"),
        index,
        widenPublic = false
      )
    assertEquals(results.size, 2)
    val constructors = results.collect { case a: UsageResult.Abstractable =>
      a.constructor
    }
    assertEquals(constructors.toSet.size, 2)
  }

  test("isWidenable rejects public without widenPublic") {
    assert(
      !UsageAnalyzer.isWidenable(definition("publicMap"), widenPublic = false)
    )
  }

  test("isWidenable accepts a restricted owner chain") {
    assert(
      UsageAnalyzer.isWidenable(
        definition("restrictedOwner"),
        widenPublic = false
      )
    )
  }

  test("the stdlib table alone resolves List#map to the Functor map root") {
    assertEquals(
      UsageAnalyzer
        .analyze(definition("mapOnly"), baseIndex, widenPublic = false)
        .collect { case UsageResult.Abstractable(_, _, _, _, ops) => ops }
        .flatten
        .map(_.method)
        .toSet,
      Set(Symbol("cats/Functor#map()."))
    )
  }

  test("an indexed stdlib decline remains a decline") {
    UsageAnalyzer
      .analyze(definition("missingCapability"), baseIndex, widenPublic = false)
      .collectFirst { case declined: UsageResult.Declined =>
        declined.reason
      } match {
      case Some(_: DeclineReason.OrderOrIndexSpecific) => ()
      case Some(_: DeclineReason.NoCapability)         => ()
      case other => fail(s"expected a decline, got $other")
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
    abstractable(name).ops
      .map(_.method)
      .toSet

  private def abstractable(name: String): UsageResult.Abstractable =
    UsageAnalyzer
      .analyze(definition(name), index, widenPublic = false)
      .collectFirst { case result: UsageResult.Abstractable => result }
      .getOrElse(fail(s"$name was not abstractable"))

  private def decline(
      name: String,
      widenPublic: Boolean = false
  ): UsageResult.Declined =
    UsageAnalyzer
      .analyze(definition(name), index, widenPublic = widenPublic)
      .collectFirst { case declined: UsageResult.Declined => declined }
      .getOrElse(fail(s"$name did not decline"))

  private def definition(name: String): Defn.Def =
    doc.tree
      .collect {
        case defn: Defn.Def if defn.name.value == name => defn
      }
      .headOption
      .getOrElse(fail(s"missing fixture definition: $name"))

  private def selectedMethod(defn: Defn.Def): Symbol =
    defn.body
      .collect { case Term.Select(_, method: Term.Name) =>
        method.symbol
      }
      .headOption
      .getOrElse(fail(s"missing selected method in ${defn.name.value}"))
}
