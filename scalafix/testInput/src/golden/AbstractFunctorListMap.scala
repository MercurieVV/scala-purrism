/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.functor._

case class User(name: String)

object AbstractFunctorListMap {
  private def names(us: List[User]): List[String] = us.map(_.name)
}
