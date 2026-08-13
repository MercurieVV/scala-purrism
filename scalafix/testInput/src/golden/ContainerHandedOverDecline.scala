/*
rules = [PreferContainerTypeclasses]
 */
package golden

final class ContainerHandedOverDecline {
  private def names(users: List[String]): List[String] = // assert: PreferContainerTypeclasses
    users.map(user => user.toUpperCase)

  val asValue: List[String] => List[String] = names
}
