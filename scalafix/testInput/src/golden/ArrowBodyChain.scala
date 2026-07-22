/*
rules = [PreferArrow]

# Body-only linear chain. The def already returns a Kleisli (bare, here), so the
# rewrite happens inside the `Kleisli { id => ... }` and leaves the signature
# alone -- the entry point that reaches the idiomatic corpus, where composition
# lives inside Kleisli lambdas of already-Kleisli-typed members.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class BodyChainFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String],
    summarise: Kleisli[F, String, Int]
) {
  def enrich: Kleisli[F, String, Int] =
    Kleisli { id =>
      for {
        user   <- loadUser.run(id)
        orders <- loadOrders.run(user)
        result <- summarise.run(orders)
      } yield result
    }
}
