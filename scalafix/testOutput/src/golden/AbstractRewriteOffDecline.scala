
package golden

final case class Widget(name: String)

final class AbstractRewriteOffDecline {
  private def names(items: List[Widget]): List[String] = 
    items.map(item => item.name)
}
