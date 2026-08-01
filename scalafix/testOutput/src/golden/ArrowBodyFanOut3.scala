
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.arrow._

object ArrowBodyFanOut3 {
  type -->[F[_], A, B] = Kleisli[F, A, B]

  final class Checks[F[_]: Monad](
      filesChanged: Kleisli[F, String, Boolean],
      hasCommits: Kleisli[F, String, Boolean],
      hasPr: Kleisli[F, String, Boolean]
  ) {
    def changedPlan: -->[F, String, (Boolean, Boolean, Boolean)] =
      (filesChanged &&& hasCommits &&& hasPr).map({
  case ((a1, a2), a3) =>
    (a1, a2, a3)
})
  }
}
