/*
rules = [TypeclassWeakening]

# The target would be written `Functor`, but `Functor` already names a
# different type in this file: the added import would be shadowed, so the
# bound is kept.
 */
package golden

import cats.effect.Sync
import cats.syntax.all.*

object WeakenNameClash {
  trait Functor[F[_]]

  def bump[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)
}
