/*
rules = [PreferTypeParameters]
 */
package golden

import cats.Eval
import cats.syntax.coflatMap.*
import cats.syntax.comonad.*

final class TypeParametersUmbrella {

  /** The container: `PreferPolymorphicCollections` widens `List` to `S[_]`. */
  private def names(users: List[String]): List[String] =
    users.map(user => user.toUpperCase)

  /** The element: `PreferPolymorphicCollectionOps` renames `mkString` and bounds the
    * element, because `mkString_` renders with `Show`.
    */
  private def rendered(rows: List[String]): String =
    rows.mkString("[", ",", "]")

  /** Any other unary constructor: `PreferPolymorphicTypeclasses` widens `Eval`. */
  private def duplicate(e: Eval[Int]): Eval[Int] =
    e.coflatMap(w => w.extract + 1)
}
