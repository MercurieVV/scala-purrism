/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.containers = []
PreferPolymorphicTypeclasses.rewrite = false
 */
package golden

final case class Widget(name: String)

final class AbstractRewriteOffDecline {
  private def names(items: List[Widget]): List[String] = // assert: PreferPolymorphicTypeclasses.constructor-rewrite-off
    items.map(item => item.name)
}
