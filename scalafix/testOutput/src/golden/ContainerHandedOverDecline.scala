
package golden

final class ContainerHandedOverDecline {
  private def names(users: List[String]): List[String] = 
    users.map(user => user.toUpperCase)

  val asValue: List[String] => List[String] = names
}
