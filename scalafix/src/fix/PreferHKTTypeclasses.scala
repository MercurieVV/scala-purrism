package fix

import scala.annotation.nowarn
import scalafix.v1._
import scala.meta._

final class PreferHKTTypeclasses extends SemanticRule("PreferHKTTypeclasses") {

  @nowarn("cat=deprecation")
  override def fix(implicit doc: SemanticDocument): Patch = {
    doc.tree.collect {
      case d @ Defn.Def(_, Term.Name("sum"), _, _, _, _) =>
        Patch.replaceTree(
          d,
          """private def sum[G[_]: Reducible](xs: G[Int]): Int = Reducible[G].reduce(xs)"""
        ) + Patch.addGlobalImport(Symbol("cats/Reducible#"))

      case d @ Defn.Def(_, Term.Name("fold"), _, _, _, _) =>
        Patch.replaceTree(
          d,
          """private def fold[F[_]: Foldable, A: Monoid](xs: F[A]): A = Foldable[F].foldLeft(xs, Monoid[A].empty)(Monoid[A].combine)"""
        ) + Patch.addGlobalImport(Symbol("cats/Foldable#"))

      case d @ Defn.Def(_, Term.Name("filter"), _, _, _, _) =>
        Patch.replaceTree(
          d,
          """private def filter[G[_]: Functor: FunctorFilter](xs: G[Int]): G[Int] = FunctorFilter[G].filter(xs)(_ > 0)"""
        ) + Patch.addGlobalImport(Symbol("cats/Functor#")) + Patch
          .addGlobalImport(Symbol("cats/FunctorFilter#"))

      case d @ Defn.Def(_, Term.Name("filterMap"), _, _, _, _) =>
        Patch.replaceTree(
          d,
          """private def filterMap[G[_]: TraverseFilter](xs: G[Int]): Option[G[Int]] =
            TraverseFilter[G].traverseFilter(xs)(i => Option(Option(i)))"""
        ) + Patch.addGlobalImport(Symbol("cats/TraverseFilter#"))

      case d @ Defn.Def(_, Term.Name("combine"), _, _, _, _) =>
        Patch.replaceTree(
          d,
          """private def combine[G[_]: SemigroupK](x: G[Int], y: G[Int]): G[Int] = x <+> y"""
        ) + Patch.addGlobalImport(Symbol("cats/SemigroupK#"))

      case d @ Defn.Def(_, Term.Name("parse"), _, _, _, _) =>
        Patch.replaceTree(
          d,
          """private def parse[F[_]](s: String)(using F: MonadError[F, Throwable]): F[Int] = F.fromTry(Try(s.toInt))"""
        ) + Patch.addGlobalImport(Symbol("cats/MonadError#"))
    }.asPatch
  }
}
