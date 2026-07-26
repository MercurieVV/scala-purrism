/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.SemigroupK
import cats.syntax.semigroupk._

private def combine[G[_]: SemigroupK](x: G[Int], y: G[Int]): G[Int] = x <+> y
