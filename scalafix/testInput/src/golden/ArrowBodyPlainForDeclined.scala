/*
rules = [PreferArrow]

# The same plain-effect `for` body as ArrowBodyAggressiveLift, but WITHOUT
# `PreferArrow.aggressive`. In the conservative default the generators call
# plain effectful methods (not Kleislis), so no conservative path matches and
# the aggressive lifting is not attempted: the body is left exactly as written.
# This is the negative that proves the flag -- and only the flag -- unlocks the
# lifting. No expected-output file: the rule must produce zero change.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyPlainForDeclined {
  trait Service[F[_]] {
    def size(path: Int): F[Int]
    def active(branch: Int): F[Boolean]
  }

  final class Checks[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), (Int, Int, Boolean)] =
      Kleisli { ctx =>
        for {
          size   <- service.size(ctx._1)
          active <- service.active(ctx._2)
        } yield (ctx._1, size, active)
      }
  }
}
