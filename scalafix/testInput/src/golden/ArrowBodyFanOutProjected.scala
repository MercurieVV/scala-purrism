/*
rules = [PreferArrow]

# Body-only fan-out where each branch reads a different *projection* of the
# input, the shape the reference corpus uses (`k.run(task.field)`). Each
# projected branch becomes a `.local`; two of them sit exactly at the
# readability budget, so this rewrites, while a third projection would tip it
# over and be declined.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyFanOutProjected {
  final case class Request(path: String, branch: String)

  final class Checks[F[_]: Monad](
      loadPath: Kleisli[F, String, Int],
      loadBranch: Kleisli[F, String, Boolean]
  ) {
    def inspect: Kleisli[F, Request, (Int, Boolean)] =
      Kleisli { request =>
        for {
          size   <- loadPath.run(request.path)
          active <- loadBranch.run(request.branch)
        } yield (size, active)
      }
  }
}
