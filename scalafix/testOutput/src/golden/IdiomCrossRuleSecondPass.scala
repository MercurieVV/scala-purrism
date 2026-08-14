
package golden

import cats.effect.Sync
import cats.syntax.all.*

/** What `IdiomCrossRule` produces, fed back in.
  *
  * One rule's output is another's input: `PreferOptionIdioms` turns
  * `map(f).getOrElse(F.unit)` into a `fold`, and the `fold` is what
  * `PreferEffectIdioms` recognises. Neither sees the other's work within a
  * single pass, because both match the text scalafix handed them. So the set
  * converges over two runs rather than one, and this fixture is the second.
  */
final class IdiomCrossRuleSecondPass[F[_]: Sync] {
  def announce(name: Option[String], log: String => F[Unit]): F[Unit] =
    name.traverse_(log)
}
