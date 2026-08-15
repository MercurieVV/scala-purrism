/*
rules = [PreferPolymorphicCollections, PreferPolymorphicTypeclasses]
 */
package golden

final class ContainerAndHktTogether {
  private def names(users: List[String]): List[String] =
    users.map(user => user.toUpperCase)
}
