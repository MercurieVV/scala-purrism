/*
rules = [SimplifyCatsExpressions]

# mproduct pairs the values in the order they were bound. Here the pair is the
# other way round, so the rewrite would swap the tuple's fields. This file must
# come back unchanged.
 */
package golden

import cats.Monad
import cats.syntax.all.*

final class CatsNegativeReversedPair[F[_]: Monad] {
  def paired(ids: F[Int], load: Int => F[String]): F[(String, Int)] =
    ids.flatMap(id => load(id).map(name => (name, id)))
}
