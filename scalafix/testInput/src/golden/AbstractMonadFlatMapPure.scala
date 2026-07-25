/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.applicative._
import cats.syntax.flatMap._

object AbstractMonadFlatMapPure {
  private def mkList(x: Int): List[Int] =
    List(x).flatMap(a => List(a + 1)).flatMap(b => b.pure[List])
}
