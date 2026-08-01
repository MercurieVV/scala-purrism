/*
rules = [PreferCatsFunctions]

# `_.foo` and `f(_)` are spellings of a lambda, not a different computation, so
# they must normalize to the same IR as the longhand `x => x.foo`. Before the
# Normalizer desugared them, any body written with an underscore lambda failed
# to normalize and was silently skipped -- most idiomatic Scala is written this
# way. Explicit type arguments are erased for the same reason.
 */
package golden

import cats.FunctorFilter
import cats.syntax.all._

final class UnderscoreLambda[F[_]: FunctorFilter] {
  def viaUnderscore(fa: F[Option[Int]]): F[Int] = fa.mapFilter(identity(_))

  def viaTypeArgs(fa: F[Option[Int]]): F[Int] = fa.mapFilter[Int](identity)
}
