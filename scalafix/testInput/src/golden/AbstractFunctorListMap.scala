/*
rules = [PreferHKTTypeclasses]
 */
package golden

case class User(name: String)

object AbstractFunctorListMap {
  private def names(us: List[User]): List[String] = us.map(_.name)
}
