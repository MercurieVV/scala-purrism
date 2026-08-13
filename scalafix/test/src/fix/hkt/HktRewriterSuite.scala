package fix.hkt

import scala.meta._

import munit.FunSuite

import scalafix.internal.patch.PatchInternals
import scalafix.rule.RuleName
import scalafix.testkit.FixtureDocuments
import scalafix.v1.SemanticDocument
import scalafix.v1.Symbol
import scalafix.v1.XtensionTreeScalafix

final class HktRewriterSuite extends FunSuite {
  private val preferredNames = List("G", "H", "K")
  private lazy val index = CatsIndex.load()
  private val functor = Symbol("cats/Functor#")
  private val functorMap = Symbol("cats/Functor#map().")
  private val solution = CapabilitySolver.Solution(List(functor), Nil, 1)

  test("freshTypeParamName chooses G for a clean definition") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterNames.scala")
    assertEquals(
      HktRewriter.freshTypeParamName(definition("clean"), preferredNames),
      Some("G")
    )
  }

  test("freshTypeParamName skips an enclosing G") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterNames.scala")
    assertEquals(
      HktRewriter.freshTypeParamName(definition("enclosing"), preferredNames),
      Some("H")
    )
  }

  test("freshTypeParamName returns None when G, H, and K are taken") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterNames.scala")
    assertEquals(
      HktRewriter.freshTypeParamName(definition("allTaken"), preferredNames),
      None
    )
  }

  test("requiredImports deduplicates and stable-sorts two constraints") {
    val twoConstraints = CapabilitySolver.Solution(
      List(Symbol("cats/FunctorFilter#"), functor, functor),
      Nil,
      1
    )
    assertEquals(
      HktRewriter.requiredImports(twoConstraints, index),
      List("cats.Functor", "cats.FunctorFilter")
    )
    assertEquals(index.syntaxImport(functorMap), Some("cats.syntax.functor.*"))
  }

  test("rewrite renders the source's context-bound style") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterContextStyle.scala")
    val fixed =
      applyPatch(HktRewriter.rewrite(usage("transform"), solution, index, "G"))

    assertEquals(
      fixed,
      """/*
        |rules = [DisableSyntax]
        | */
        |package golden
        |
        |import cats.Applicative
        |import cats.Functor
        |import cats.syntax.functor._
        |
        |object HktRewriterStyleCases {
        |  private def style[F[_]: Applicative](value: F[Int]): F[Int] = value
        |
        |  private def transform[G[_]: Functor](values: G[Int]): G[Int] =
        |    values.map(identity)
        |}
        |""".stripMargin
    )
  }

  test("rewrite renders the source's using style") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterUsingStyle.scala")
    val fixed =
      applyPatch(HktRewriter.rewrite(usage("transform"), solution, index, "G"))

    assertEquals(
      fixed,
      """/*
        |rules = [DisableSyntax]
        | */
        |package golden
        |
        |import cats.Applicative
        |import cats.Functor
        |import cats.syntax.functor._
        |
        |object HktRewriterUsingStyleCases {
        |  private def style[F[_]](value: F[Int])(using Applicative[F]): F[Int] = value
        |
        |  private def transform[G[_]](values: G[Int])(using G: Functor): G[Int] =
        |    values.map(identity)
        |}
        |""".stripMargin
    )
  }

  test("rewrite reuses a stronger enclosing constraint") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterExistingReuse.scala")
    val fixed =
      applyPatch(HktRewriter.rewrite(usage("transform"), solution, index, "H"))

    assertEquals(
      fixed,
      """/*
        |rules = [DisableSyntax]
        | */
        |package golden
        |
        |import cats.Traverse
        |import cats.syntax.functor._
        |
        |final class HktRewriterExistingReuse[G[_]: Traverse] {
        |  private def transform(values: G[Int]): G[Int] =
        |    values.map(identity)
        |}
        |""".stripMargin
    )
  }

  test("rewrite preserves nested constructors and honors cats.syntax.all") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterOuterOnly.scala")
    val fixed =
      applyPatch(HktRewriter.rewrite(usage("transform"), solution, index, "G"))

    assertEquals(
      fixed,
      """/*
        |rules = [DisableSyntax]
        | */
        |package golden
        |
        |import cats.Functor
        |import cats.syntax.all.*
        |
        |object HktRewriterOuterOnly {
        |  private def transform[G[_]: Functor](values: G[List[Int]]): G[List[Int]] =
        |    values.map(identity)
        |}
        |""".stripMargin
    )
  }

  test("rewrite keeps a concrete pinned error type") {
    implicit val doc: SemanticDocument =
      FixtureDocuments("src/golden/HktRewriterPinnedError.scala")
    val monadError = CapabilitySolver.Solution(
      List(Symbol("cats/MonadError#")),
      List("E"),
      10
    )
    val fixed = applyPatch(
      HktRewriter.rewrite(
        usage("parse").copy(ops = Nil),
        monadError,
        index,
        "F"
      )
    )

    assertEquals(
      fixed,
      """/*
        |rules = [DisableSyntax]
        | */
        |package golden
        |
        |import scala.util.Try
        |import cats.MonadError
        |
        |object HktRewriterPinnedError {
        |  private def parse[F[_]](value: F[Int])(using F: MonadError[F, Throwable]): F[Int] = value
        |}
        |""".stripMargin
    )
  }

  private def usage(
      name: String
  )(implicit doc: SemanticDocument): UsageResult.Abstractable = {
    val defn = definition(name)
    val target = defn.paramClauseGroups
      .flatMap(_.paramClauses)
      .flatMap(_.values)
      .flatMap(_.decltpe)
      .collectFirst { case applied: Type.Apply => applied }
      .getOrElse(fail(s"missing applied parameter type: $name"))
    UsageResult.Abstractable(
      defn,
      target,
      target.tpe.symbol,
      target.argClause.values.head,
      List(RequiredOp(functorMap, defn.body.pos, KindShape.Unary))
    )
  }

  private def definition(
      name: String
  )(implicit doc: SemanticDocument): Defn.Def =
    doc.tree
      .collect {
        case defn: Defn.Def if defn.name.value == name => defn
      }
      .headOption
      .getOrElse(fail(s"missing fixture definition: $name"))

  private def applyPatch(
      patch: scalafix.v1.Patch
  )(implicit doc: SemanticDocument): String =
    PatchInternals
      .semantic(Map(RuleName("HktRewriter") -> patch), doc, suppress = false)
      .fixed
}
