/*
rules = [PreferArrow]

# The reference half of the veto pair: `enrich` is passed as a value, in a file
# that does not declare it. See ArrowLiftVetoDef.scala.
 */
package crossfile

import cats.Monad

object ArrowLiftVetoUse {
  def consume[F[_]: Monad](step: String => F[String]): F[String] =
    step("seed")

  def describe[F[_]: Monad]: F[String] =
    consume(ArrowLiftVetoDef.enrich[F])
}
