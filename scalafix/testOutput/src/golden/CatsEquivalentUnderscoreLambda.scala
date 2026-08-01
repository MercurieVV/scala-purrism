package golden

import cats.FunctorFilter
import cats.syntax.all._

final class UnderscoreLambda[F[_]: FunctorFilter] {
  def viaUnderscore(fa: F[Option[Int]]): F[Int] = fa.flattenOption

  def viaTypeArgs(fa: F[Option[Int]]): F[Int] = fa.flattenOption
}
