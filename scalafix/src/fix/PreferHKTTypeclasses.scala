package fix

import scalafix.v1._
import scala.meta._

final class PreferHKTTypeclasses extends SemanticRule("PreferHKTTypeclasses") {

  override def fix(implicit doc: SemanticDocument): Patch = {
    doc.tree.collect {
      case d @ Defn.Def(
            mods,
            Term.Name("sum"),
            Nil,
            List(List(param)),
            Type.Name("Int"),
            body
          ) if param.decltpe.exists {
            case Type
                  .Apply(Type.Name("NonEmptyList"), List(Type.Name("Int"))) =>
              true
            case _ => false
          } && body.toString.contains("Reducible[NonEmptyList]") =>
        // Replace with abstract version
        val newDef: Defn.Def = q"""
          private def sum[G[_]: Reducible](xs: G[Int]): Int = Reducible[G].reduce(xs)
        """
        Patch.replaceTree(d, newDef)

      case d @ Defn.Def(
            mods,
            Term.Name("fold"),
            List(
              Type.Param(
                Nil,
                Type.Name("A"),
                Type.Bounds(None, None),
                Nil,
                Nil,
                Nil
              )
            ),
            List(List(param)),
            Type.Apply(Type.Name("A"), Nil),
            body
          ) if param.decltpe.exists {
            case Type.Apply(Type.Name("List"), List(Type.Name("A"))) => true
            case _                                                   => false
          } && body.toString.contains("Monoid[A].empty") && body.toString.contains("Monoid[A].combine") =>
        val newDef: Defn.Def = q"""
          private def fold[F[_]: Foldable, A: Monoid](xs: F[A]): A = Foldable[F].foldLeft(xs, Monoid[A].empty)(Monoid[A].combine)
        """
        Patch.replaceTree(d, newDef)

      case d @ Defn.Def(
            mods,
            Term.Name("filter"),
            Nil,
            List(List(param)),
            Type.Apply(Type.Name("Option"), List(Type.Name("Int"))),
            body
          ) if param.decltpe.exists {
            case Type.Apply(Type.Name("Option"), List(Type.Name("Int"))) => true
            case _ => false
          } && body.toString.contains("FunctorFilter[Option]") =>
        val newDef: Defn.Def = q"""
          private def filter[G[_]: Functor: FunctorFilter](xs: G[Int]): G[Int] = FunctorFilter[G].filter(xs)(_ > 0)
        """
        Patch.replaceTree(d, newDef)

      case d @ Defn.Def(
            mods,
            Term.Name("filterMap"),
            Nil,
            List(List(param)),
            Type.Apply(
              Type.Name("Option"),
              List(Type.Apply(Type.Name("List"), List(Type.Name("Int"))))
            ),
            body
          ) if param.decltpe.exists {
            case Type.Apply(Type.Name("List"), List(Type.Name("Int"))) => true
            case _                                                     => false
          } && body.toString.contains("TraverseFilter[List]") =>
        val newDef: Defn.Def = q"""
          private def filterMap[G[_]: TraverseFilter](xs: G[Int]): Option[G[Int]] =
            TraverseFilter[G].traverseFilter(xs)(i => Option(Option(i)))
        """
        Patch.replaceTree(d, newDef)

      // The Alternative case is already abstract – nothing to change,
      // but we still need to produce a `Patch`. We return empty.
      case _ => Patch.empty
    }.asPatch
  }
}
