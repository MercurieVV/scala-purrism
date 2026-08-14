
package golden

import scala.concurrent.duration.FiniteDuration

import cats.effect.Async
import cats.syntax.all.*

final class IdiomTraverseUnit[F[_]: Async] {
  def pause(delay: Option[FiniteDuration]): F[Unit] =
    delay.traverse_(Async[F].sleep)

  /** A block argument keeps its own braces rather than gaining a pair. */
  def announce(name: Option[String], log: String => F[Unit]): F[Unit] =
    name.traverse_ { value =>
      log(value.trim)
    }
}
