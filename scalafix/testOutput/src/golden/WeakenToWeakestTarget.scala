package golden

import cats.syntax.all.*
import cats.{Applicative, Functor, Monad}

object WeakenToWeakestTarget {
  def bump[F[_]: Functor](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def paired[F[_]: Applicative](fa: F[Int]): F[(Int, Int)] =
    fa.map(identity).product(1.pure[F])

  def all[F[_]: Monad](xs: List[Int], f: Int => F[Int]): F[List[Int]] =
    xs.traverse(f).map(identity)

  def chain[F[_]: Monad](fa: F[Int], f: Int => F[Int]): F[Int] =
    fa.flatMap(f)

  def comprehension[F[_]: Monad](fa: F[Int]): F[Int] =
    (for {
      a <- fa
    } yield a).map(identity)

  def viaHelper[F[_]: Applicative](fa: F[Int]): F[Int] =
    twice(fa).map(identity)

  private def twice[G[_]: cats.Applicative](g: G[Int]): G[Int] =
    g.map2(g)(_ + _)

  final class Service[F[_]: Monad] {
    def load(seed: F[Int]): F[Int] =
      seed.flatMap(value => normalize(value.pure[F]))

    private def normalize[G[_]: Functor](value: G[Int]): G[Int] =
      value.map(identity)
  }
}
