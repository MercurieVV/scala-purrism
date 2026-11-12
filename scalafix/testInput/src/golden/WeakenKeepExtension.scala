/*
rules = [TypeclassWeakening]

# An extension method of a trait or non-final class can be overridden like
# any other member, so its bound is kept; one in an object is weakened.
 */
package golden

import cats.effect.Sync
import cats.syntax.all.*

object WeakenKeepExtension {
  trait Ops {
    extension (fa: List[Int])
      def bumpAll[F[_]: Sync](f: Int => F[Int]): F[List[Int]] =
        fa.traverse(f)
  }

  object FinalOps {
    extension (fa: List[Int])
      def bumpAll[F[_]: Sync](f: Int => F[Int]): F[List[Int]] =
        fa.traverse(f)
  }
}
