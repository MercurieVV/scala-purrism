/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Reducible
import cats.data.NonEmptyList

private def sum(xs: NonEmptyList[Int]): Int = Reducible[NonEmptyList].reduce(xs)
