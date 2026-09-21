/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.containers = []
 */
package golden

final case class Item(name: String)

final class AbstractHandedOverDecline {
  private def names(items: List[Item]): List[String] = // assert: PreferPolymorphicTypeclasses.constructor-handed-over-as-value
    items.map(item => item.name)

  val asValue: List[Item] => List[String] = names
}
