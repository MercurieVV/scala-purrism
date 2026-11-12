package golden

import cats.effect.Sync
import cats.syntax.all.*
import cats.{Applicative, Apply, FlatMap, Functor, Monad}

object WeakenToWeakestTarget {
  def bump[F[_]: Functor](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def paired[F[_]: Apply](fa: F[Int], fb: F[Int]): F[(Int, Int)] =
    fa.product(fb).map(identity)

  def lifted[F[_]: Applicative]: F[Int] =
    1.pure[F].map(_ + 1)

  def all[F[_]: Applicative](xs: List[Int], f: Int => F[Int]): F[List[Int]] =
    xs.traverse(f)

  def chain[F[_]: FlatMap](fa: F[Int], f: Int => F[Int]): F[Int] =
    fa.flatMap(f)

  def chainPure[F[_]: Monad](fa: F[Int]): F[Int] =
    fa.flatMap(a => (a + 1).pure[F])

  def comprehension[F[_]: FlatMap](fa: F[Int], fb: F[Int]): F[Int] =
    for {
      a <- fa
      b <- fb
    } yield a + b

  def viaHelper[F[_]: Applicative](fa: F[Int]): F[Int] =
    twice(fa).map(identity)

  private def twice[G[_]: Applicative](g: G[Int]): G[Int] =
    g.map2(1.pure[G])(_ + _)

  final class Service[F[_]: Monad] {
    def load(seed: F[Int]): F[Int] =
      seed.flatMap(value => normalize(value.pure[F]))

    private def normalize[G[_]: Functor](value: G[Int]): G[Int] =
      value.map(identity)
  }

  final class Shadowed[F[_]: Functor] {
    def run(fa: F[Int]): F[Int] =
      fa.map(_ + 1)

    def inner[F[_]: Sync](x: => Int): F[Int] =
      Sync[F].delay(x)
  }
}
