
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.choice._
import cats.syntax.compose._

final class ChoiceFlow[F[_]: Monad](
    classify: Kleisli[F, String, Either[Int, String]],
    onLeft: Kleisli[F, Int, Boolean],
    onRight: Kleisli[F, String, Boolean]
) {
  def decide: Kleisli[F, String, Boolean] =
    classify >>> (onLeft ||| onRight)
}
