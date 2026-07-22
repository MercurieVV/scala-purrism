
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.arrow._

object ArrowBodyFanOutTupled {
  final case class Request(path: String, branch: String)

  final class Checks[F[_]: Monad](
      loadSize: Kleisli[F, String, Int],
      check: Kleisli[F, (String, String), Boolean]
  ) {
    def inspect: Kleisli[F, Request, (Int, Boolean)] =
      loadSize.local((request: Request) => request.path) &&& check.local((request: Request) => (request.path, request.branch))
  }
}
