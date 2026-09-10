package fix.vocabulary

import scala.meta.inputs.Position

final case class BudgetOverage(
    typeAtOrClassPos: Position,
    typeclass: String,
    count: Int,
    max: Int
)

object ConversionBudget {

  /** `typeArgTuples`: one entry per typeclass requirement found anywhere in
    * scope -- the typeclass FQCN, its two type-argument renderings, and the
    * position to anchor a diagnostic on for that occurrence (the nearest
    * enclosing class/trait/object definition, per `docs/RULES.md`'s "report at
    * the granularity of the decision"). The anchor of a reported overage is the
    * first occurrence's position for that typeclass.
    */
  def overages(
      typeArgTuples: List[(String, List[String], Position)],
      budgeted: Set[String],
      maxInstantiations: Int
  ): List[BudgetOverage] =
    typeArgTuples
      .filter { case (typeclass, _, _) => budgeted.contains(typeclass) }
      .groupBy(_._1)
      .toList
      .flatMap { case (typeclass, entries) =>
        val distinctTuples = entries.map(_._2).distinct
        if (distinctTuples.size > maxInstantiations)
          List(
            BudgetOverage(
              entries.head._3,
              typeclass,
              distinctTuples.size,
              maxInstantiations
            )
          )
        else Nil
      }
}
