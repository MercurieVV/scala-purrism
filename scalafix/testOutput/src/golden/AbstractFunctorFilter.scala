/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Functor
import cats.FunctorFilter
import cats.syntax.functorFilter._

private def filter[G[_]: Functor: FunctorFilter](xs: G[Int]): G[Int] = xs.filter(_ > 0)
