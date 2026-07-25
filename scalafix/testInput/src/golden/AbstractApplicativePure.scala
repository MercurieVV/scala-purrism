/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.applicative._
import cats.syntax.functor._

object AbstractApplicativePure {
  private def wrap(x: Int): List[Int] = x.pure[List].map(_ + 1)
}
