/*
rules = [PreferEffectIdioms]
 */
package golden

import scala.concurrent.duration.FiniteDuration

import cats.effect.Async
import cats.syntax.all.*

final class IdiomTraverseUnit[F[_]: Async] {
  def pause(delay: Option[FiniteDuration]): F[Unit] =
    delay.fold(Async[F].unit)(Async[F].sleep)

  /** A block argument keeps its own braces rather than gaining a pair. */
  def announce(name: Option[String], log: String => F[Unit]): F[Unit] =
    name.fold(Async[F].unit) { value =>
      log(value.trim)
    }
}
