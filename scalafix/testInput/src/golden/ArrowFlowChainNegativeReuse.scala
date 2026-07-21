/*
rules = [PreferArrow]

# Pattern A negative: the intermediate binding `user` is used twice — once
# as the argument to the next Kleisli, once again in the final `map`. A
# straight `andThen` chain would drop the value `user` needs downstream, so
# this must be left untouched.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class ChainReuseFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String],
    summarise: Kleisli[F, String, String]
) {
  def enrich(id: String): F[String] =
    loadUser.run(id).flatMap { user =>
      loadOrders.run(user).flatMap { orders =>
        summarise.run(orders).map(summary => user + summary)
      }
    }
}
