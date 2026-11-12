/*
rules = [TypeclassWeakening]

# Each bound becomes the single weakest ancestor that covers what the body
# needs: intermediates (Apply, FlatMap) included. Needs are read from the
# compiler's evidence synthetics, so `traverse` (Applicative evidence) and
# for-comprehensions are explained without name lists. A local helper
# contributes its own weakened bound; a class and its helper are decided
# together. An inner `F` shadowing the class's `F` constrains only itself.
 */
package golden

import cats.effect.Sync
import cats.syntax.all.*

object WeakenToWeakestTarget {
  def bump[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def paired[F[_]: Sync](fa: F[Int], fb: F[Int]): F[(Int, Int)] =
    fa.product(fb).map(identity)

  def lifted[F[_]: Sync]: F[Int] =
    1.pure[F].map(_ + 1)

  def all[F[_]: Sync](xs: List[Int], f: Int => F[Int]): F[List[Int]] =
    xs.traverse(f)

  def chain[F[_]: Sync](fa: F[Int], f: Int => F[Int]): F[Int] =
    fa.flatMap(f)

  def chainPure[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.flatMap(a => (a + 1).pure[F])

  def comprehension[F[_]: Sync](fa: F[Int], fb: F[Int]): F[Int] =
    for {
      a <- fa
      b <- fb
    } yield a + b

  def viaHelper[F[_]: Sync](fa: F[Int]): F[Int] =
    twice(fa).map(identity)

  private def twice[G[_]: Sync](g: G[Int]): G[Int] =
    g.map2(1.pure[G])(_ + _)

  final class Service[F[_]: Sync] {
    def load(seed: F[Int]): F[Int] =
      seed.flatMap(value => normalize(value.pure[F]))

    private def normalize[G[_]: Sync](value: G[Int]): G[Int] =
      value.map(identity)
  }

  final class Shadowed[F[_]: Sync] {
    def run(fa: F[Int]): F[Int] =
      fa.map(_ + 1)

    def inner[F[_]: Sync](x: => Int): F[Int] =
      Sync[F].delay(x)
  }
}
