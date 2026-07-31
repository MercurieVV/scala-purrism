/*
rules = [PreferHKTTypeclasses]

PreferHKTTypeclasses.widenPublic = false
 */
package golden

final case class User(name: String)

object AbstractPublicBoundaryDecline {
  def names(us: List[User]): List[String] = // assert: PreferHKTTypeclasses
    us.map(_.name)
}
