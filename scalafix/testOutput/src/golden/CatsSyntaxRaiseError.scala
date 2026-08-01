
package golden

import cats.MonadThrow
import cats.syntax.all._

final class CatsSyntaxRaiseError[F[_]: MonadThrow] {
  def fail(error: Throwable): F[String] =
    error.raiseError[F, String]
}
