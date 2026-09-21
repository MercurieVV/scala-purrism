/*
rules = [TypelevelPurrism]

# Under the umbrella rule scalafix prints the umbrella's name as the lint-id
# prefix -- `[TypelevelPurrism.fan-out-shadowed-input]`, not
# `[PreferArrow.fan-out-shadowed-input]` -- because `TypelevelPurrism.fix`
# sums the child patches and every lint in the sum is attributed to it. The
# kind is the same; kinds are unique across the catalog, so a harness joins on
# the kind. The body is `ArrowFlowFanOutNegativeShadow`'s: the second branch
# rebinds `id`, so the fan-out is declined and only the lint remains.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

final class UmbrellaFindingPrefix[F[_]: Monad](
    loadUserAge: Kleisli[F, String, Int],
    loadSettings: Kleisli[F, String, Boolean]
) {
  def profile(id: String): F[(Int, Boolean)] =
    loadUserAge.run(id).flatMap { age =>
      val id = age.toString
      loadSettings.run(id).map(active => (age, active)) // assert: TypelevelPurrism.fan-out-shadowed-input
    }
}
