/*
rules = [TypeclassWeakening]

# Once every bound naming `Monad` is weakened, nothing in the file refers to
# it any more, so its import goes with it (an unused import would warn).
 */
package golden

import cats.Monad
import cats.syntax.all.*

object WeakenRemovesImport {
  def twice[F[_]: Monad](fa: F[Int]): F[Int] =
    fa.map(_ * 2)
}
