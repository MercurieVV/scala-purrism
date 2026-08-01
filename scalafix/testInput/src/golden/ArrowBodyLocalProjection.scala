/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# The body *is* a single Kleisli call, fed a projection of the arrow input:
# `Kleisli { split => summarise((split.id, split.label)).as(done) }`. The callee
# is already a Kleisli, so the surrounding `Kleisli { ... }` is pure ceremony --
# `.local` expresses the projection and the wrapper goes.
#
# This is the shape a codebase lands in right after `PreferKleisli` has lifted
# the callee: the call site keeps whatever wrapper it always had, and only the
# argument list changed. Previously only a *fan-out branch* got the `.local`
# treatment, so a body that was nothing but the call stayed wrapped.
#
# The trailing `.as(...)` is peeled like a trailing `.map`. It has to be:
# unrecognised, it hides the call underneath and the whole site is declined.
#
# Needs the flag, for a reason worth recording: the rewrite is structurally
# cheaper than the source (one plumbing node, no wrapper) but *textually*
# longer, because `.local` must annotate its parameter with the input type or
# the new input infers as `Any`. The conservative budget's secondary
# length-ratio guard therefore declines it. That guard is left alone rather
# than widened -- it is the backstop against pathological blow-up -- so this
# shape is aggressive-only.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.functor._

object ArrowBodyLocalProjection {
  final class Reporter[F[_]: Monad] {
    val summarise: Kleisli[F, (Int, String), Unit] =
      Kleisli { case (id, label) => Monad[F].unit }

    def report: Kleisli[F, (Int, String, Boolean), String] =
      Kleisli { entry =>
        summarise((entry._1, entry._2)).as("done")
      }
  }

  def summariseWith[G[_]: Monad](tag: String): Kleisli[G, (Int, String), Unit] =
    Kleisli { case (id, label) => Monad[G].unit }

  // The callee is a *curried application*, not a name -- which is exactly what
  // `PreferKleisli` leaves behind when it lifts a def that has a callback
  // parameter (`comment(progress)((root, task))`). An application node carries
  // no symbol, so asking it for its type answers nothing; the head has to be
  // resolved and its supplied parameter lists counted instead.
  final class CurriedReporter[F[_]: Monad] {
    def report: Kleisli[F, (Int, String, Boolean), String] =
      Kleisli { entry =>
        summariseWith[F]("tag")((entry._1, entry._2)).as("done")
      }
  }

  // The tail reshape closes over the arrow input -- `entry._2` appears in the
  // constant the `.as` produces. Point-free has no name for the input there,
  // so the conservative budget declines. Aggressive mode carries it along with
  // a leading `Kleisli.ask` and destructures it back out, which is the
  // plumbing-for-coverage trade the flag exists to make.
  final class CapturingReporter[F[_]: Monad] {
    def report: Kleisli[F, (Int, String, Boolean), String] =
      Kleisli { entry =>
        summariseWith[F]("tag")((entry._1, entry._2)).as(s"done: ${entry._2}")
      }
  }
}
