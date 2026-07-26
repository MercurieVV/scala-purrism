
package golden

final case class User(name: String)

object AbstractPublicBoundaryDecline {
  def names(us: List[User]): List[String] = 
    us.map(_.name)
}
