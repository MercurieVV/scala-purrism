/*
rules = [PreferArrow]
PreferArrow.aggressive = true

# Aggressive fan-out where one arm ignores the arrow input entirely
# (`service.total` takes no argument). Independence -- no generator reading
# another's binding -- is what makes `&&&` faithful to the `for`; reading the
# input is not required of *every* arm, only of at least one, otherwise the
# body is a plain `mapN` over constants with nothing arrow-shaped to gain.
#
# Tuple input/output on purpose, as in `ArrowBodyAggressiveLift`: case-class
# fields would register as opaque-type candidates in the shared `testInput`
# index and perturb `OpaqueCandidateExplorerSuite`.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyAggressiveConstArm {
  trait Service[F[_]] {
    def size(path: Int): F[Int]
    def total: F[Int]
  }

  final class Totals[F[_]: Monad](service: Service[F]) {
    def inspect: Kleisli[F, (Int, Int), (Int, Int)] =
      Kleisli { ctx =>
        for {
          size  <- service.size(ctx._1)
          total <- service.total
        } yield (size, total)
      }
  }
}
