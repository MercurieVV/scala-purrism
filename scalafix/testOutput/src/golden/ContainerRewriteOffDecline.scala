
package golden

final class ContainerRewriteOffDecline {
  private def names(users: List[String]): List[String] = 
    users.map(user => user.toUpperCase)
}
