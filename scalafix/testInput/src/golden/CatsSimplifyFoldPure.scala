/*
rules = [SimplifyCatsExpressions]
 */
package golden

import cats.Applicative
import cats.syntax.all.*

final class CatsSimplifyFoldPure[F[_]: Applicative] {
  def orDefault(id: Option[Int], load: Int => F[String]): F[String] =
    id.fold(Applicative[F].pure("missing"))(load)
}
