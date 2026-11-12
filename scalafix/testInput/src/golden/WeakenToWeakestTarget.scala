/*
rules = [TypeclassWeakening]

# The weakened bound is the weakest of Functor, Applicative and Monad that
# covers what the body uses -- not Monad for every body. Intermediate classes
# (Apply, FlatMap, Semigroupal) are never targets: a body needing them is
# lifted to the next of the three. A for-comprehension desugars to calls the
# tree does not show, so it stays at Monad.
 */
package golden

import cats.effect.Sync
import cats.syntax.all.*

object WeakenToWeakestTarget {
  def bump[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def paired[F[_]: Sync](fa: F[Int]): F[(Int, Int)] =
    fa.map(identity).product(1.pure[F])

  def all[F[_]: Sync](xs: List[Int], f: Int => F[Int]): F[List[Int]] =
    xs.traverse(f).map(identity)

  def chain[F[_]: Sync](fa: F[Int], f: Int => F[Int]): F[Int] =
    fa.flatMap(f)

  def comprehension[F[_]: Sync](fa: F[Int]): F[Int] =
    (for {
      a <- fa
    } yield a).map(identity)

  def viaHelper[F[_]: Sync](fa: F[Int]): F[Int] =
    twice(fa).map(identity)

  private def twice[G[_]: cats.Applicative](g: G[Int]): G[Int] =
    g.map2(g)(_ + _)

  final class Service[F[_]: Sync] {
    def load(seed: F[Int]): F[Int] =
      seed.flatMap(value => normalize(value.pure[F]))

    private def normalize[G[_]: Sync](value: G[Int]): G[Int] =
      value.map(identity)
  }
}
