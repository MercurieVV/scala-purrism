package golden.typelevel

import cats.Applicative
import cats.FlatMap
import cats.MonadThrow
import cats.syntax.all.*

final class CatsExpressionSyntax[F[_]] {
  def build(id: String): F[String] =
    id.pure[F]

  def fail(error: Throwable): F[String] =
    error.raiseError[F, String]

  def label(seed: F[Int]): F[String] =
    seed.map(value => s"id-$value")

  def next(seed: F[Int]): F[String] =
    seed.flatMap(value => value.toString.pure[F])
}
