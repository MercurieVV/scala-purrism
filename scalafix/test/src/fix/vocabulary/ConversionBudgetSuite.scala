package fix.vocabulary

import munit.FunSuite
import scala.meta.inputs.Position

class ConversionBudgetSuite extends FunSuite {
  private val pos = Position.None

  test("at the max: no overage") {
    val tuples = List(("ArrowConvert", List("Step1", "Step2"), pos))
    assertEquals(ConversionBudget.overages(tuples, Set("ArrowConvert"), 1), Nil)
  }

  test("one over the max: reports the overage") {
    val tuples = List(
      ("ArrowConvert", List("Step1", "Step2"), pos),
      ("ArrowConvert", List("Step2", "Step3"), pos)
    )
    val result = ConversionBudget.overages(tuples, Set("ArrowConvert"), 1)
    assertEquals(result.map(_.count), List(2))
  }

  test("the same tuple required twice counts once") {
    val tuples = List(
      ("ArrowConvert", List("Step1", "Step2"), pos),
      ("ArrowConvert", List("Step1", "Step2"), pos)
    )
    assertEquals(ConversionBudget.overages(tuples, Set("ArrowConvert"), 1), Nil)
  }

  test("a non-budgeted typeclass is never counted") {
    val tuples = List(("SomeOtherTypeclass", List("Step1", "Step2"), pos))
    assertEquals(ConversionBudget.overages(tuples, Set("ArrowConvert"), 1), Nil)
  }

  test("the overage is anchored at the first occurrence's position") {
    val firstPos = Position.Range(scala.meta.inputs.Input.String("x"), 0, 0)
    val tuples = List(
      ("ArrowConvert", List("Step1", "Step2"), firstPos),
      ("ArrowConvert", List("Step2", "Step3"), pos)
    )
    val result = ConversionBudget.overages(tuples, Set("ArrowConvert"), 1)
    assertEquals(result.map(_.typeAtOrClassPos), List(firstPos))
  }
}
