package fix.weakening

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.concurrent.duration.*

import fix.hkt.CatsIndex
import fix.hkt.CatsTypeclass
import fix.hkt.KindShape
import fix.hkt.tasty.CacheConfig
import fix.hkt.tasty.RuntimeIndex
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scalafix.v1.Symbol

final class BoundSolverSuite extends ScalaCheckSuite {

  override val munitTimeout: Duration = 10.minutes

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
        Set(Sync),
        CacheConfig(
          Some(Files.createTempDirectory("bound-solver-suite")),
          false
        )
      )
      .fold(fail(_), identity)

  private def replaced(d: Decision): Option[Rendered] = d match {
    case Decision.Replace(t) => Some(t)
    case _                   => None
  }

  test("Sync with Functor weakens to Functor") {
    assertEquals(
      replaced(BoundSolver.solve(Sync, Set(s("Functor")), index)).map(_.symbol),
      Some(s("Functor"))
    )
  }

  test("Sync with Functor and Semigroupal weakens to Apply") {
    assertEquals(
      replaced(
        BoundSolver.solve(Sync, Set(s("Functor"), s("Semigroupal")), index)
      ).map(_.symbol),
      Some(s("Apply"))
    )
  }

  test("Sync with FlatMap weakens to FlatMap") {
    assertEquals(
      replaced(BoundSolver.solve(Sync, Set(s("FlatMap")), index)).map(_.symbol),
      Some(s("FlatMap"))
    )
  }

  test("Sync with FlatMap and Applicative weakens to Monad") {
    assertEquals(
      replaced(
        BoundSolver.solve(Sync, Set(s("FlatMap"), s("Applicative")), index)
      ).map(_.symbol),
      Some(s("Monad"))
    )
  }

  test("binary target is rendered through its alias") {
    val t = replaced(BoundSolver.solve(Sync, Set(s("ApplicativeError")), index))
    assertEquals(t.map(_.symbol), Some(s("ApplicativeError")))
    assertEquals(t.map(_.renderName), Some("ApplicativeThrow"))
    assertEquals(t.map(_.importPath), Some("cats.ApplicativeThrow"))
  }

  test("alias bound with other fixed args never offers a mismatched alias") {
    assertEquals(
      BoundSolver.solve(
        s("MonadError"),
        Set(s("ApplicativeError")),
        index,
        List("java/lang/String#")
      ),
      Decision.Keep
    )
  }

  test("alias bound with matching fixed args weakens through the alias") {
    assertEquals(
      replaced(
        BoundSolver.solve(
          s("MonadError"),
          Set(s("ApplicativeError")),
          index,
          List("java/lang/Throwable#")
        )
      ).map(_.renderName),
      Some("ApplicativeThrow")
    )
  }

  test("Traverse with Functor and Foldable keeps") {
    assertEquals(
      BoundSolver.solve(
        s("Traverse"),
        Set(s("Functor"), s("Foldable")),
        index
      ),
      Decision.Keep
    )
  }

  test("Keep cases") {
    assertEquals(BoundSolver.solve(Sync, Set.empty, index), Decision.Keep)
    assertEquals(BoundSolver.solve(Sync, Set(Sync), index), Decision.Keep)
    assertEquals(
      BoundSolver.solve(s("Functor"), Set(s("Monad")), index),
      Decision.Keep
    )
  }

  private def tc(
      name: String,
      parents: List[String],
      depth: Int,
      binary: Boolean = false
  ): (Symbol, CatsTypeclass) =
    s(name) -> CatsTypeclass(
      s(name),
      parents.map(s),
      KindShape.Unary,
      if (binary) 2 else 1,
      depth,
      name,
      s"x.$name",
      true
    )

  test("diamond with unrenderable top is ambiguous, ordered by symbol") {
    val diamond = new CatsIndex(
      Map(
        tc("A", Nil, 0, binary = true),
        tc("B", List("A"), 1),
        tc("C", List("A"), 1),
        tc("E", List("B", "C"), 2)
      ),
      Map.empty,
      Map.empty
    )
    val d = BoundSolver.solve(s("E"), Set(s("A")), diamond)
    d match {
      case Decision.Ambiguous(options) =>
        assertEquals(options.map(_.symbol), List(s("B"), s("C")))
      case other => fail(s"expected Ambiguous, got $other")
    }
  }

  property("Replace covers required and is idempotent") {
    val bounds = index.publicTypeclasses.filter(t => t.typeParamCount == 1)
    val gen = for {
      b <- Gen.oneOf(bounds)
      ancestors = index.typeclasses.keys
        .filter(index.isAncestor(_, b.symbol))
        .toList
      r <- Gen.someOf(ancestors).map(_.toSet)
    } yield (b.symbol, r)
    forAll(gen) { case (b, r) =>
      BoundSolver.solve(b, r, index) match {
        case Decision.Replace(t) =>
          val ok = (t.symbol == b || index.isAncestor(t.symbol, b)) &&
            r.forall(x => x == t.symbol || index.isAncestor(x, t.symbol))
          val args = index
            .aliasesOf(t.symbol)
            .find(_.renderName == t.renderName)
            .map(_.fixedArgs)
            .getOrElse(Nil)
          ok && BoundSolver.solve(t.symbol, r, index, args) == Decision.Keep
        case _ => true
      }
    }
  }
}
