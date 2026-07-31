/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Monoid

private def fold[A: Monoid](xs: List[A]): A = xs.foldLeft(Monoid[A].empty)(Monoid[A].combine)
