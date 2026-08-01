
package golden

import cats.FunctorFilter
import cats.Functor

private def filter[G[_]: Functor: FunctorFilter](xs: G[Int]): G[Int] = FunctorFilter[G].filter(xs)(_ > 0)
