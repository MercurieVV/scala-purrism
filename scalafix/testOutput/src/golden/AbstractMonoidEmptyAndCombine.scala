
package golden

import cats.Monoid
import cats.Foldable

private def fold[F[_]: Foldable, A: Monoid](xs: F[A]): A = Foldable[F].foldLeft(xs, Monoid[A].empty)(Monoid[A].combine)
