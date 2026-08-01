package fix.hkt

import scala.meta._

import munit.FunSuite

import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument
import scalafix.v1.Symbol

final class UsageAnalyzerSmokeSuite extends FunSuite {
  private implicit lazy val doc: SemanticDocument =
    FixtureDocuments("src/hkt/UsageAnalyzerSmoke.scala")

  private lazy val index: CatsIndex = CatsIndex.load()

  test("private List.map requires the Functor map override root") {
    val results =
      UsageAnalyzer.analyze(definition("mapOnly"), index, widenPublic = false)

    results match {
      case List(UsageResult.Abstractable(_, _, _, _, ops)) =>
        assertEquals(
          ops.map(_.method),
          List(Symbol("cats/Functor#map()."))
        )
      case other =>
        fail(s"expected one Abstractable result, got $other")
    }
  }

  test("private List.head declines as order- or index-specific") {
    val results =
      UsageAnalyzer.analyze(definition("headOnly"), index, widenPublic = false)

    results.collectFirst {
      case UsageResult.Declined(
            _,
            DeclineReason.OrderOrIndexSpecific(what)
          ) =>
        what
    } match {
      case Some(what) =>
        assertEquals(what, "head")
      case None =>
        fail(s"expected an OrderOrIndexSpecific decline, got $results")
    }
  }

  private def definition(name: String): Defn.Def =
    doc.tree
      .collect { case defn: Defn.Def if defn.name.value == name => defn }
      .headOption
      .getOrElse(fail(s"missing fixture definition: $name"))
}
