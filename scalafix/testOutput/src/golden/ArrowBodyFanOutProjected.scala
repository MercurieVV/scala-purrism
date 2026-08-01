
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.arrow._

object ArrowBodyFanOutProjected {
  final case class Request(path: String, branch: String)

  final class Checks[F[_]: Monad](
      loadPath: Kleisli[F, String, Int],
      loadBranch: Kleisli[F, String, Boolean]
  ) {
    def inspect: Kleisli[F, Request, (Int, Boolean)] =
      loadPath.local((request: Request) => request.path) &&& loadBranch.local((request: Request) => request.branch)
  }
}
