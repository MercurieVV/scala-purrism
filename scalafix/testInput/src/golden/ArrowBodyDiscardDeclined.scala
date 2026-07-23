/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# The two discard-generator shapes the rule refuses, even in aggressive mode.
# No output file: both bodies are left exactly as written.
#
# `Interleaved` puts a discard *between* two named generators. `&&&` feeds both
# its arms the same input and has no position to run an effect in between, so
# expressing this would mean moving the discard next to one of its neighbours
# -- a different sequence from the one the `for` specified.
#
# `TrailingReadsInput` has a trailing discard that reads the arrow input. That
# discard is rendered inside `.flatTap`, where only the arms' results are
# bound; carrying the input in as well would cost an `ask` arm plus a wider
# tuple, more plumbing than the `for` it replaces.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyDiscardDeclined {
  trait Service[F[_]] {
    def size(path: Int): F[Int]
    def active(branch: Int): F[Boolean]
    def record(size: Int): F[Unit]
    def announce(label: Int): F[Unit]
  }

  final class Interleaved[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), (Int, Boolean)] =
      Kleisli { ctx =>
        for {
          size   <- service.size(ctx._1)
          _      <- service.record(size)
          active <- service.active(ctx._2)
        } yield (size, active)
      }
  }

  final class TrailingReadsInput[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), (Int, Boolean)] =
      Kleisli { ctx =>
        for {
          size   <- service.size(ctx._1)
          active <- service.active(ctx._2)
          _      <- service.announce(ctx._2)
        } yield (size, active)
      }
  }
}
