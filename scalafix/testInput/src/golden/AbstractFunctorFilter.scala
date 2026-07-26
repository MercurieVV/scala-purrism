/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.functorFilter._

private def filter(xs: Option[Int]): Option[Int] = xs.filter(_ > 0)
