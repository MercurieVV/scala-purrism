/*
rules = [DisableSyntax]

# PreferArrow does not exist yet; this is a source-of-truth fixture for it,
# not a rewrite fixture. DisableSyntax is configured with nothing here so it
# changes no text, which is why this file needs no matching testOutput entry.
#
# Shared negative: `.run` is called on a `Pipe`, not a `Kleisli`. Same shape
# as the chain regression fixture, but the receiver type must gate the
# rewrite, so this must be left untouched.
 */
package golden

import cats.Monad
import cats.syntax.flatMap._

final case class Pipe[F[_], A, B](f: A => F[B]) {
  def run(a: A): F[B] = f(a)
}

final class NonKleisliFlow[F[_]: Monad](
    loadUser: Pipe[F, String, String],
    loadOrders: Pipe[F, String, String]
) {
  def enrich(id: String): F[String] =
    loadUser.run(id).flatMap(user => loadOrders.run(user))
}
