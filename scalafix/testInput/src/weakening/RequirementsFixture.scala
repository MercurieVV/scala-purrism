/*
rules = [DisableSyntax]
 */
package weakening

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

  final class Shadow[F[_]: Sync](outer: F[Int]) {
    def outerMap: F[Int] = outer.map(_ + 1)

    def inner[F[_]: Sync](fa: F[Int]): F[Int] =
      Sync[F].delay(1).flatMap(_ => fa)
  }
}
