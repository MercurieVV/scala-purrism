package fix.architecture

import scala.meta._

/** Counts distinct `ArrowConvert[P, Q]` requirements (by their `(P, Q)`
  * type-name pair, not by call site) across a trait/case-class and its
  * companion object combined, per the spec's "Conversion budget".
  */
object ConversionBudget {

  def violations(
      anchor: Tree,
      members: List[Stat],
      params: List[Term.Param],
      max: Int
  ): List[ArchitectureFinding] = {
    val pairs = (members.flatMap(requirementsIn) ++ params.flatMap(
      requirementsInParam
    )).distinct
    if (pairs.size > max)
      List(
        ArchitectureFinding(
          anchor,
          s"requires ${pairs.size} distinct ArrowConvert conversions " +
            s"(${pairs.map { case (p, q) => s"$p -> $q" }.mkString(", ")}); " +
            s"at most $max allowed (maxArrowConversions)"
        )
      )
    else Nil
  }

  private def requirementsIn(stat: Stat): List[(String, String)] = stat match {
    case c: Defn.Class =>
      c.ctor.paramClauses.flatMap(_.values).flatMap(requirementsInParam).toList
    case d: Defn.Def =>
      d.paramClauseGroups
        .flatMap(_.paramClauses.flatMap(_.values))
        .flatMap(requirementsInParam)
    case _ => Nil
  }

  private def requirementsInParam(param: Term.Param): List[(String, String)] =
    param.decltpe.toList.flatMap {
      case apply: Type.Apply =>
        Type.Apply.After_4_6_0.unapply(apply) match {
          case Some((Type.Name("ArrowConvert"), args)) =>
            args.values match {
              case List(Type.Name(p), Type.Name(q)) => List((p, q))
              case _                                => Nil
            }
          case _ => Nil
        }
      case _ => Nil
    }
}
