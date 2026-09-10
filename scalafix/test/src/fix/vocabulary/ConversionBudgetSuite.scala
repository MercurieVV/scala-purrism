package fix.vocabulary

import munit.FunSuite
import scala.meta.inputs.Position

class ConversionBudgetSuite extends FunSuite {
  private val pos = Position.None

  test("at the max: no overage") {
    val tuples = List("ArrowConvert" -> List("Step1", "Step2"))
    assertEquals(
      ConversionBudget.overages(pos, tuples, Set("ArrowConvert"), 1),
      Nil
    )
  }

  test("one over the max: reports the overage") {
    val tuples = List(
      "ArrowConvert" -> List("Step1", "Step2"),
      "ArrowConvert" -> List("Step2", "Step3")
    )
    val result = ConversionBudget.overages(pos, tuples, Set("ArrowConvert"), 1)
    assertEquals(result.map(_.count), List(2))
  }

  test("the same tuple required twice counts once") {
    val tuples = List(
      "ArrowConvert" -> List("Step1", "Step2"),
      "ArrowConvert" -> List("Step1", "Step2")
    )
    assertEquals(
      ConversionBudget.overages(pos, tuples, Set("ArrowConvert"), 1),
      Nil
    )
  }

  test("a non-budgeted typeclass is never counted") {
    val tuples = List("SomeOtherTypeclass" -> List("Step1", "Step2"))
    assertEquals(
      ConversionBudget.overages(pos, tuples, Set("ArrowConvert"), 1),
      Nil
    )
  }
}
