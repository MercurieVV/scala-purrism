package fix.hkt

import scala.meta.inputs.Position
import scala.util.Random

import munit.FunSuite

import scalafix.v1.Symbol

final class CapabilitySolverSuite extends FunSuite {
  private lazy val index: CatsIndex = CatsIndex.load()

  test("map requires Functor") {
    assertSolution(ops("cats/Functor#map()."), List("cats/Functor#"), 1)
  }

  test("flatMap and pure prefer Monad over stronger and wider covers") {
    assertSolution(
      ops("cats/FlatMap#flatMap().", "cats/Applicative#pure()."),
      List("cats/Monad#"),
      8
    )
  }

  test("flatMap and map reduce to FlatMap") {
    assertSolution(
      ops("cats/FlatMap#flatMap().", "cats/Functor#map()."),
      List("cats/FlatMap#"),
      5
    )
  }

  test("map and pure require Applicative") {
    assertSolution(
      ops("cats/Functor#map().", "cats/Applicative#pure()."),
      List("cats/Applicative#"),
      6
    )
  }

  test("combineK and empty require MonoidK") {
    assertSolution(
      ops("cats/SemigroupK#combineK().", "cats/MonoidK#empty()."),
      List("cats/MonoidK#"),
      1
    )
  }

  test("error operations and flatMap require MonadError with E") {
    val solution = solve(
      ops(
        "cats/ApplicativeError#raiseError().",
        "cats/ApplicativeError#handleErrorWith().",
        "cats/FlatMap#flatMap()."
      )
    )
    assertEquals(solution.constraints, symbols("cats/MonadError#"))
    assertEquals(solution.extraTypeParams, List("E"))
  }

  test("map and mapFilter retain separate Functor and FunctorFilter constraints") {
    assertSolution(
      ops("cats/Functor#map().", "cats/FunctorFilter#mapFilter()."),
      List("cats/Functor#", "cats/FunctorFilter#"),
      1
    )
  }

  test("pure, combineK, and empty require Alternative") {
    assertSolution(
      ops(
        "cats/Applicative#pure().",
        "cats/SemigroupK#combineK().",
        "cats/MonoidK#empty()."
      ),
      List("cats/Alternative#"),
      10
    )
  }

  test("an unindexed operation declines with NoCapability") {
    CapabilitySolver.solve(
      ops("example/Missing#operation()."),
      index,
      maxConstraints = 2
    ) match {
      case Left(_: DeclineReason.NoCapability) => ()
      case other                               => fail(s"expected NoCapability, got $other")
    }
  }

  test("a too-wide uncovered antichain declines") {
    CapabilitySolver.solve(
      ops(
        "cats/Functor#map().",
        "cats/FunctorFilter#mapFilter().",
        "cats/SemigroupK#combineK()."
      ),
      index,
      maxConstraints = 2
    ) match {
      case Left(DeclineReason.TooManyConstraints(candidate, 2)) =>
        assertEquals(candidate, symbols("cats/Functor#", "cats/FunctorFilter#", "cats/SemigroupK#"))
      case other => fail(s"expected TooManyConstraints, got $other")
    }
  }

  test("solve is deterministic across shuffled operations and index map iteration") {
    val required = ops("cats/FlatMap#flatMap().", "cats/Applicative#pure().")
    val expected = CapabilitySolver.solve(required, index, maxConstraints = 2)
    val random = new Random(40L)

    (1 to 100).foreach { _ =>
      val shuffledIndex = new CatsIndex(
        random.shuffle(index.typeclasses.toList).toMap,
        random.shuffle(index.capabilities.toList).toMap,
        random.shuffle(index.syntax.toList).toMap
      )
      assertEquals(
        CapabilitySolver.solve(random.shuffle(required), shuffledIndex, maxConstraints = 2),
        expected
      )
    }
  }

  test("rank orders every unit case strictly") {
    val cases = List(
      ops("cats/Functor#map()."),
      ops("cats/FlatMap#flatMap().", "cats/Applicative#pure()."),
      ops("cats/FlatMap#flatMap().", "cats/Functor#map()."),
      ops("cats/Functor#map().", "cats/Applicative#pure()."),
      ops("cats/SemigroupK#combineK().", "cats/MonoidK#empty()."),
      ops(
        "cats/ApplicativeError#raiseError().",
        "cats/ApplicativeError#handleErrorWith().",
        "cats/FlatMap#flatMap()."
      ),
      ops("cats/Functor#map().", "cats/FunctorFilter#mapFilter()."),
      ops(
        "cats/Applicative#pure().",
        "cats/SemigroupK#combineK().",
        "cats/MonoidK#empty()."
      )
    )
    cases.foreach { required =>
      CapabilitySolver.rank(CapabilitySolver.candidates(required, index), index).take(2) match {
        case first :: second :: Nil => assertNotEquals(first, second)
        case _                      => ()
      }
    }
  }

  private def assertSolution(required: List[RequiredOp], expected: List[String], strength: Int): Unit = {
    val solution = solve(required)
    assertEquals(solution.constraints, symbols(expected*))
    assertEquals(solution.strengthSum, strength)
  }

  private def solve(required: List[RequiredOp]): CapabilitySolver.Solution =
    CapabilitySolver.solve(required, index, maxConstraints = 2) match {
      case Right(solution) => solution
      case Left(reason)    => fail(reason.message)
    }

  private def ops(methods: String*): List[RequiredOp] =
    methods.toList.map(method => RequiredOp(Symbol(method), Position.None, KindShape.Unary))

  private def symbols(values: String*): List[Symbol] = values.toList.map(Symbol(_))
}
