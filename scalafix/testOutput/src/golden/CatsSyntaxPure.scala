
package golden

import cats.Applicative
import cats.syntax.all._

final class CatsSyntaxPure[F[_]: Applicative] {
  def build(id: String): F[String] =
    id.pure[F]
}
