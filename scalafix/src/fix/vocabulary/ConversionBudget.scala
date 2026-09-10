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
    * scope -- the typeclass FQCN, its two type-argument renderings, the simple
    * name of the nearest enclosing class/trait/object (a trait/case-class and
    * its companion object share a name, so grouping by it merges exactly that
    * pair per the spec, without merging two unrelated classes in the same
    * file), and the position to anchor a diagnostic on for that occurrence. The
    * anchor of a reported overage is the first occurrence's position within its
    * (typeclass, ownerName) group.
    */
  def overages(
      typeArgTuples: List[(String, List[String], String, Position)],
      budgeted: Set[String],
      maxInstantiations: Int
  ): List[BudgetOverage] =
    typeArgTuples
      .filter { case (typeclass, _, _, _) => budgeted.contains(typeclass) }
      .groupBy { case (typeclass, _, ownerName, _) => (typeclass, ownerName) }
      .toList
      .flatMap { case ((typeclass, _), entries) =>
        val distinctTuples = entries.map(_._2).distinct
        if (distinctTuples.size > maxInstantiations)
          List(
            BudgetOverage(
              entries.head._4,
              typeclass,
              distinctTuples.size,
              maxInstantiations
            )
          )
        else Nil
      }
}
