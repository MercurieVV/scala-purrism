/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.Bifunctor

object AbstractBifunctorEitherBimap {
  def transform[E, A, B, C](either: Either[E, A], f: E => B, g: A => C): Either[B, C] = {
    val bifunctor = implicitly[Bifunctor[Either]]
    bifunctor.bimap(either)(f, g)
  }
}
