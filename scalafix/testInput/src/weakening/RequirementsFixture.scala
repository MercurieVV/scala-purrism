/*
rules = [DisableSyntax]
 */
package weakening

import cats.Functor
import cats.Monad
import cats.MonadThrow
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.all.*

private object RequirementsFixture {
  def mapOnly[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def mapProductPure[F[_]: Sync](fa: F[Int]): F[(Int, Int)] =
    fa.map(_ + 1).product(2.pure[F])

  def traverseList[F[_]: Sync](xs: List[Int], f: Int => F[Int]): F[List[Int]] =
    xs.traverse(f)

  def flatMapOnly[F[_]: Sync](fa: F[Int], f: Int => F[Int]): F[Int] =
    fa.flatMap(f)

  def comprehension[F[_]: Sync](fa: F[Int], fb: F[Int]): F[Int] =
    for {
      a <- fa
      b <- fb
    } yield a + b

  def helper[F[_]: Sync](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def usesHelper[F[_]: Sync](fa: F[Int]): F[Int] =
    helper(fa)

  def external[F[_]: Sync]: F[Random[F]] =
    Random.scalaUtilRandom[F]

  def aliasBound[F[_]: MonadThrow](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def monadHelper[F[_]: Monad](fa: F[Int]): F[Int] =
    fa.map(_ + 1)

  def generatorPure[F[_]: Monad](fb: F[Int]): F[Int] =
    for {
      x <- 1.pure[F]
      y <- fb
    } yield x + y

  def generatorMap[F[_]: Monad](fa: F[Int], fb: F[Int]): F[Int] =
    for {
      x <- fa.map(_ + 1)
      y <- fb
    } yield x + y

  def generatorHelper[F[_]: Monad](fa: F[Int], fb: F[Int]): F[Int] =
    for {
      x <- monadHelper(fa)
      y <- fb
    } yield x + y

  def localGenerator[F[_]: Sync](fa: F[Int]): F[Int] = {
    def loc[G[_]: Sync](g: G[Int]): G[Int] = g
    for {
      x <- loc(fa)
      y <- fa
    } yield x + y
  }

  def useEvidence[G[_]](g: Sync[G], ga: G[Int]): G[Int] = ga

  def namedPassed[F[_]: Sync as S](fa: F[Int]): F[Int] =
    useEvidence(S, fa)

  def namedMap[F[_]: Sync as S](fa: F[Int]): F[Int] =
    S.map(fa)(_ + 1)

  def namedGiven[F[_]: Sync as S](fa: F[Int]): F[Int] = {
    given Functor[F] = S
    fa.map(_ + 1)
  }

  trait Profiles[F[_]] {
    def fetch(id: String): F[Int]
  }

  final class Tagless[F[_]: Sync](profiles: Profiles[F]) {
    def load(seed: F[String]): F[Int] =
      seed.flatMap(id => profiles.fetch(id))
  }

  def useFunctor[G[_]](fa: G[Int])(functor: Functor[G]): G[Int] =
    functor.map(fa)(_ + 1)

  def derivedPassed[F[_]: Sync as S](fa: F[Int]): F[Int] = {
    val derived: Functor[F] = S
    useFunctor(fa)(derived)
  }

  final class Shadow[F[_]: Sync](outer: F[Int]) {
    def outerMap: F[Int] = outer.map(_ + 1)

    def inner[F[_]: Sync](fa: F[Int]): F[Int] =
      Sync[F].delay(1).flatMap(_ => fa)
  }
}
