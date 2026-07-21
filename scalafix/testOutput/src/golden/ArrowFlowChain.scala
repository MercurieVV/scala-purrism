package golden

import cats.Monad
import cats.data.Kleisli

final class ChainFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String],
    summarise: Kleisli[F, String, String]
) {
  def enrich: Kleisli[F, String, String] =
    loadUser.andThen(loadOrders).andThen(summarise)

  def enrichFor: Kleisli[F, String, String] =
    loadUser.andThen(loadOrders).andThen(summarise)
}
