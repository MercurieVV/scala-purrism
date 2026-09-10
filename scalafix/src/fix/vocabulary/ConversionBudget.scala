package fix.vocabulary

import scala.meta.inputs.Position

final case class BudgetOverage(
    typeAtOrClassPos: Position,
    typeclass: String,
    count: Int,
    max: Int
)

object ConversionBudget {
  def overages(
      classPos: Position,
      typeArgTuples: List[(String, List[String])],
      budgeted: Set[String],
      maxInstantiations: Int
  ): List[BudgetOverage] =
    typeArgTuples
      .filter { case (typeclass, _) => budgeted.contains(typeclass) }
      .groupBy(_._1)
      .toList
      .flatMap { case (typeclass, entries) =>
        val distinctTuples = entries.map(_._2).distinct
        if (distinctTuples.size > maxInstantiations)
          List(
            BudgetOverage(
              classPos,
              typeclass,
              distinctTuples.size,
              maxInstantiations
            )
          )
        else Nil
      }
}
