/*
rules = [PreferContainerTypeclasses]
 */
package golden

final class ContainerFoldable {
  private def names(users: List[String]): List[String] =
    users.map(user => user.toUpperCase)

  def render(users: List[String]): List[String] = names(users)
}
