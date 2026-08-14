
package golden

import cats.syntax.coflatMap.*
import cats.syntax.comonad.*
import cats.{Comonad, Foldable, Functor}
import cats.syntax.foldable._
import cats.syntax.functor._

final class TypeParametersUmbrella {

  /** The container: `PreferContainerTypeclasses` widens `List` to `S[_]`. */
  private def names[S[_]: Functor](users: S[String]): S[String] =
    users.map(user => user.toUpperCase)

  /** The element: `PreferElementTypeclasses` renames `mkString` and bounds the
    * element, because `mkString_` renders with `Show`.
    */
  private def rendered[S[_]: Foldable](rows: S[String]): String =
    rows.mkString_("[", ",", "]")

  /** Any other unary constructor: `PreferHKTTypeclasses` widens `Eval`. */
  private def duplicate[G[_]: Comonad](e: G[Int]): G[Int] =
    e.coflatMap(w => w.extract + 1)
}
