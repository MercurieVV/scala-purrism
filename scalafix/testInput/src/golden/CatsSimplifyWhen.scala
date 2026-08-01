/*
rules = [SimplifyCatsExpressions]
 */
package golden

import cats.Applicative
import cats.syntax.all.*

final class CatsSimplifyWhen[F[_]: Applicative] {
  def guarded(enabled: Boolean, record: F[Unit]): F[Unit] =
    if (enabled) record else Applicative[F].unit

  def skipped(disabled: Boolean, record: F[Unit]): F[Unit] =
    if (disabled) Applicative[F].unit else record
}
