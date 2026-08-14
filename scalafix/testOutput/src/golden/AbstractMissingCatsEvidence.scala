
package golden

import cats.Foldable
import cats.syntax.foldable._
object AbstractMissingCatsEvidence {
  // `foldMap` needs `Monoid[B]` as well as a `Foldable` container, and the rule
  // does not check element-level evidence -- it widens the container and leaves
  // the evidence question to the compiler. Here the definition answers it
  // itself, so the widened form still compiles.
  private def total[B, G[_]: Foldable](xs: G[B]): B = {
    implicit def monoidB: cats.Monoid[B] = sys.error("stub")
    import cats.syntax.foldable._
    xs.foldMap(identity)
  }
}
