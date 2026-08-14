/*
rules = [DisableSyntax]
 */
package golden

import scala.util.Try
import cats.MonadError

object HktRewriterPinnedError {
  private def parse[F[_]](value: F[Int])(using MonadError[F, Throwable]): F[Int] = value
}
