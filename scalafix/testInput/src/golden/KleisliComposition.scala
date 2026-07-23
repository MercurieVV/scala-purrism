/*
rules = [PreferArrow]

# The 2-step chain `k1.run(x).flatMap(y => k2.run(y))`, lifted to a Kleisli
# signature. This used to be `PreferKleisli`'s `composition` path emitting
# `k1.andThen(k2)`; that path was deleted (it duplicated PreferArrow and could
# double-patch the same span under the umbrella rule), and PreferArrow now owns
# it, rendering `k1 >>> k2`. See docs/ARROW_PATTERNS.md.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class UserEnrichment[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String]
) {
  def enrich(id: String): F[String] =
    loadUser.run(id).flatMap(user => loadOrders.run(user))
}
