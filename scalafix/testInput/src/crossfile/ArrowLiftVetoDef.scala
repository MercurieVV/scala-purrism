/*
rules = [PreferArrow]

# A def whose body is exactly the shape signature-lifting fires on -- compare
# golden/ArrowSignaturePolyDef.scala, which does get lifted -- but which
# ArrowLiftVetoUse.scala hands over *unapplied*, as a `String => F[String]`.
#
# `Kleisli[F, String, String]` does not conform to a function type, so lifting
# this signature would compile here and break the caller one file over. The veto
# is `KleisliLiftScope.valueReferences`, shared with `PreferKleisli`, which had
# it from the start; `PreferArrow`'s own signature-lifting entry did not, and
# duly broke the reference corpus by lifting a `progress` callback out from under
# eight call sites.
#
# Negative fixture: no output file, so any patch at all fails the suite.
 */
package crossfile

import cats.Monad
import cats.data.Kleisli
import cats.syntax.flatMap._
import cats.syntax.functor._

object ArrowLiftVetoDef {
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
