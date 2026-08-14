/*
rules = [PreferHKTTypeclasses]

PreferHKTTypeclasses.widenPublic = false

# `List` is `PreferContainerTypeclasses`' by default; the visibility rule under
# test is this one's.
PreferHKTTypeclasses.containers = []
 */
package golden

final case class User(name: String)

object AbstractPublicBoundaryDecline {
  def names(us: List[User]): List[String] = // assert: PreferHKTTypeclasses
    us.map(_.name)
}
