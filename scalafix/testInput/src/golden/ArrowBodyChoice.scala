/*
rules = [PreferArrow]

# Pattern E: a Kleisli producing an `Either`, whose two arms are each a Kleisli
# applied to the matched value, collapses to `k >>> (onLeft ||| onRight)`.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class ChoiceFlow[F[_]: Monad](
    classify: Kleisli[F, String, Either[Int, String]],
    onLeft: Kleisli[F, Int, Boolean],
    onRight: Kleisli[F, String, Boolean]
) {
  def decide: Kleisli[F, String, Boolean] =
    Kleisli { request =>
      classify.run(request).flatMap {
        case Left(code)  => onLeft.run(code)
        case Right(name) => onRight.run(name)
      }
    }
}
