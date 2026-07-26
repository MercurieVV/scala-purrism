/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Foldable
import cats.Monoid

private def fold[F[_]: Foldable, A: Monoid](xs: F[A]): A = Foldable[F].foldLeft(xs, Monoid[A].empty)(Monoid[A].combine)
