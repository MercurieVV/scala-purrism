/*
rules = [PreferEffectIdioms]
 */
package golden

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

import cats.effect.Async
import cats.syntax.all.*

/** Already in the form the rule produces. Running it again must change
  * nothing: a rule that keeps rewriting its own output never converges.
  */
final class IdiomIdempotentEffects[F[_]: Async] {
  def detach(listener: AutoCloseable): Unit =
    try listener.close()
    catch { case NonFatal(_) => () }

  def pause(delay: Option[FiniteDuration]): F[Unit] =
    delay.traverse_(Async[F].sleep)
}
