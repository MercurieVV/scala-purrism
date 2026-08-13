
package golden

import scala.util.Try
import cats.syntax.all._

final case class Budget(limit: Int)
object Budget { val empty: Budget = Budget(0) }

final class IdiomOrEmpty {

  def rows(value: Option[List[String]]): List[String] = value.orEmpty

  def name(value: Option[String]): String = value.orEmpty

  def count(value: Option[Int]): Int = value.orEmpty

  def index(value: Option[Map[String, Int]]): Map[String, Int] =
    value.orEmpty

  /** A companion's own `empty` need not agree with a `Monoid`. */
  def budget(value: Option[Budget]): Budget = value.getOrElse(Budget.empty)

  /** Empty for the multiplicative monoid, which is not the one Cats picks. */
  def factor(value: Option[Int]): Int = value.getOrElse(1)

  /** `Either#getOrElse` is a different method on a different type. */
  def parsed(value: Either[String, List[Int]]): List[Int] = value.getOrElse(Nil)

  /** Same for `Try`. */
  def attempted(value: Try[String]): String = value.getOrElse("")
}
