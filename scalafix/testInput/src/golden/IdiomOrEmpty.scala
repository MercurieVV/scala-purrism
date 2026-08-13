/*
rules = [PreferOptionIdioms]
 */
package golden

import scala.util.Try

final case class Budget(limit: Int)
object Budget { val empty: Budget = Budget(0) }

final class IdiomOrEmpty {

  def rows(value: Option[List[String]]): List[String] = value.getOrElse(Nil)

  def name(value: Option[String]): String = value.getOrElse("")

  def count(value: Option[Int]): Int = value.getOrElse(0)

  def index(value: Option[Map[String, Int]]): Map[String, Int] =
    value.getOrElse(Map.empty)

  /** A companion's own `empty` need not agree with a `Monoid`. */
  def budget(value: Option[Budget]): Budget = value.getOrElse(Budget.empty)

  /** Empty for the multiplicative monoid, which is not the one Cats picks. */
  def factor(value: Option[Int]): Int = value.getOrElse(1)

  /** `Either#getOrElse` is a different method on a different type. */
  def parsed(value: Either[String, List[Int]]): List[Int] = value.getOrElse(Nil)

  /** Same for `Try`. */
  def attempted(value: Try[String]): String = value.getOrElse("")
}
