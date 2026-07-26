/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.reducible._
import cats.data.NonEmptyList

private def sum(xs: NonEmptyList[Int]): Int = xs.reduce
