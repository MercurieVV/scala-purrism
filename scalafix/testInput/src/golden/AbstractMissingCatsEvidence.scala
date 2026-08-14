/*
rules = [PreferHKTTypeclasses]

# The collections are `PreferContainerTypeclasses`' subject by default; these
# fixtures are about the shape, not about who owns `List`.
PreferHKTTypeclasses.containers = []
 */
package golden

object AbstractMissingCatsEvidence {
  // `foldMap` needs `Monoid[B]` as well as a `Foldable` container, and the rule
  // does not check element-level evidence -- it widens the container and leaves
  // the evidence question to the compiler. Here the definition answers it
  // itself, so the widened form still compiles.
  private def total[B](xs: List[B]): B = {
    implicit def monoidB: cats.Monoid[B] = sys.error("stub")
    import cats.syntax.foldable._
    xs.foldMap(identity)
  }
}
