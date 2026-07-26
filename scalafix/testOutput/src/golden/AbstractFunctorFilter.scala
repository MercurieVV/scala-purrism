/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.{Functor, FunctorFilter}

private def filter[G[_]: Functor: FunctorFilter](xs: G[Int]): G[Int] = FunctorFilter[G].filter(xs)(_ > 0)
