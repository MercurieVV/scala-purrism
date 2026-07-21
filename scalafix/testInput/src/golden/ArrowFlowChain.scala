/*
rules = [PreferArrow]

# Pattern A: a 3-step linear Kleisli chain, one entry point via nested
# `flatMap` and one via `for`. Both collapse to the same `andThen` chain.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class ChainFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String],
    summarise: Kleisli[F, String, String]
) {
  def enrich(id: String): F[String] =
    loadUser.run(id).flatMap { user =>
      loadOrders.run(user).flatMap(orders => summarise.run(orders))
    }

  def enrichFor(id: String): F[String] =
    for {
      user    <- loadUser.run(id)
      orders  <- loadOrders.run(user)
      summary <- summarise.run(orders)
    } yield summary
}
