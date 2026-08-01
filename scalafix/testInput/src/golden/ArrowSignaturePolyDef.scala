/*
rules = [PreferArrow]

# Signature-lifting a def that carries its *own* type parameters.
#
# The effect constructor of the `Kleisli[F, A, B]` return type being introduced
# is `F` -- the def's own type parameter -- so a rewrite that renders only the
# name and the new return type produces `def enrich: Kleisli[F, String, String]`
# with `F` unbound, and the project stops compiling. That is not a hypothetical:
# it is what the rule did to `progress` in the reference corpus, and nothing in
# the fixture set caught it, because every other signature-lifting fixture binds
# its effect on the enclosing class instead.
 */
package golden

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowSignaturePolyDef {
  def loadUser[F[_]: Monad]: Kleisli[F, String, String] =
    Kleisli(Monad[F].pure)

  def loadOrders[F[_]: Monad]: Kleisli[F, String, String] =
    Kleisli(Monad[F].pure)

  def enrich[F[_]: Monad](id: String): F[String] =
    for {
      user <- loadUser[F].run(id)
      orders <- loadOrders[F].run(user)
    } yield orders
}
