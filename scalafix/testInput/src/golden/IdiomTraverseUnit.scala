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
}
