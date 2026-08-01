/*
rules = [PreferArrow]

# Body-only fan-out where a branch applies a tuple-input Kleisli via Scala's
# auto-tupling -- `check(a, b)` against `Kleisli[F, (String, String), _]` -- the
# call shape the reference corpus uses (`Git[F].method(path, branch)`). The
# multi-argument call becomes a single `.local` building the tuple.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowBodyFanOutTupled {
  final case class Request(path: String, branch: String)

  final class Checks[F[_]: Monad](
      loadSize: Kleisli[F, String, Int],
      check: Kleisli[F, (String, String), Boolean]
  ) {
    def inspect: Kleisli[F, Request, (Int, Boolean)] =
      Kleisli { request =>
        for {
          size  <- loadSize.run(request.path)
          valid <- check(request.path, request.branch)
        } yield (size, valid)
      }
  }
}
