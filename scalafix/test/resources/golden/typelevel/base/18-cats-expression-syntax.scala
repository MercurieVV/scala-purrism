package golden.typelevel

import cats.Applicative
import cats.FlatMap
import cats.MonadThrow

final class CatsExpressionSyntax[F[_]] {
  def build(id: String): F[String] =
    Applicative[F].pure(id)

  def fail(error: Throwable): F[String] =
    MonadThrow[F].raiseError[String](error)

  def label(seed: F[Int]): F[String] =
    Applicative[F].map(seed)(value => s"id-$value")

  def next(seed: F[Int]): F[String] =
    FlatMap[F].flatMap(seed)(value => Applicative[F].pure(value.toString))
}
