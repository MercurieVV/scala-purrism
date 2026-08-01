/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# Aggressive mode (opt-in via `PreferArrow.aggressive`): independent `for`
# generators that call *plain* effectful methods -- not existing Kleislis --
# are lifted into `Kleisli { x => ... }` and fanned out with `&&&`. The `yield`
# still references the input, so a leading `Kleisli.ask` retains it alongside
# the fanned-out results. This is busier than the source `for`, so it is gated
# behind the flag; the conservative default declines it as too much plumbing.
#
# The input/output are tuples on purpose: a case class with value-type fields
# would register as opaque-type candidates in the shared `testInput` index and
# perturb `OpaqueCandidateExplorerSuite`, which analyses every fixture at once.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyAggressiveLift {
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
