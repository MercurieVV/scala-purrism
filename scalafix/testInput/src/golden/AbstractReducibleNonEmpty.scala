/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Reducible
import cats.data.NonEmptyList

// Not widened: the container is never the receiver of a call, only an argument
// to a summoned instance, so no capability is attributed to it. Widening the
// signature alone would leave `Reducible[NonEmptyList]` behind and not compile.
private def sum(xs: NonEmptyList[Int]): Int = Reducible[NonEmptyList].reduce(xs)
