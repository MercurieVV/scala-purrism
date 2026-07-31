/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.FunctorFilter

private def filter(xs: Option[Int]): Option[Int] = FunctorFilter[Option].filter(xs)(_ > 0)
