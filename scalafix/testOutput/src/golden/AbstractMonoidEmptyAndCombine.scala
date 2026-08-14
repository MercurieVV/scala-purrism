
package golden

import cats.Monoid
import cats.Foldable
import cats.syntax.foldable._

private def fold[A: Monoid, G[_]: Foldable](xs: G[A]): A = xs.foldLeft(Monoid[A].empty)(Monoid[A].combine)
