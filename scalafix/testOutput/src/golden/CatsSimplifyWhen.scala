
package golden

import cats.Applicative
import cats.syntax.all.*

final class CatsSimplifyWhen[F[_]: Applicative] {
  def guarded(enabled: Boolean, record: F[Unit]): F[Unit] =
    record.whenA(enabled)

  def skipped(disabled: Boolean, record: F[Unit]): F[Unit] =
    record.unlessA(disabled)
}
