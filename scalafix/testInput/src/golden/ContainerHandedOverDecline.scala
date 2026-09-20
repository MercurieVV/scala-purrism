/*
rules = [PreferPolymorphicCollections]
 */
package golden

final class ContainerHandedOverDecline {
  private def names(users: List[String]): List[String] = // assert: PreferPolymorphicCollections.handed-over-as-value
    users.map(user => user.toUpperCase)

  val asValue: List[String] => List[String] = names
}
