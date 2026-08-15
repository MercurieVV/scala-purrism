/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.widenPublic = false

# `List` is `PreferPolymorphicCollections`' by default; the visibility rule under
# test is this one's.
PreferPolymorphicTypeclasses.containers = []
 */
package golden

final case class User(name: String)

object AbstractPublicBoundaryDecline {
  def names(us: List[User]): List[String] = // assert: PreferPolymorphicTypeclasses
    us.map(_.name)
}
