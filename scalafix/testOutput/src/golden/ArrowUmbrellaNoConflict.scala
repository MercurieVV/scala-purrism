
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.compose._

final class UmbrellaFlow[F[_]: Monad](
    loadUser: Kleisli[F, String, String],
    loadOrders: Kleisli[F, String, String]
) {
  def enrich: Kleisli[F, String, String] =
  loadUser >>> loadOrders
}
