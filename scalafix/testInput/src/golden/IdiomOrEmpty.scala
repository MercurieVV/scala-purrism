/*
rules = [PreferOptionIdioms]
 */
package golden

import scala.util.Try

final case class Session(id: String)
final case class Budget(limit: Int)
object Budget { val empty: Budget = Budget(0) }

final class IdiomOrEmpty {

  def rows(value: Option[List[String]]): List[String] = value.getOrElse(Nil)

  def name(value: Option[String]): String = value.getOrElse("")

  def count(value: Option[Int]): Int = value.getOrElse(0)

  /** `Monoid[Map[K, V]]` needs a `Semigroup[V]`, because merging two maps has
    * to merge the values that collide. `Session` has none, so `orEmpty` would
    * not compile -- and the rule cannot tell the two `Map`s apart cheaply, so
    * it declines both.
    */
  def sessions(value: Option[Map[String, Session]]): Map[String, Session] =
    value.getOrElse(Map.empty)

  /** Union needs nothing of the element, so `Set` is unconditional. */
  def tags(value: Option[Set[String]]): Set[String] = value.getOrElse(Set.empty)

  /** A companion's own `empty` need not agree with a `Monoid`. */
  def budget(value: Option[Budget]): Budget = value.getOrElse(Budget.empty)

  /** Empty for the multiplicative monoid, which is not the one Cats picks. */
  def factor(value: Option[Int]): Int = value.getOrElse(1)

  /** `Either#getOrElse` is a different method on a different type. */
  def parsed(value: Either[String, List[Int]]): List[Int] = value.getOrElse(Nil)

  /** Same for `Try`. */
  def attempted(value: Try[String]): String = value.getOrElse("")
}
