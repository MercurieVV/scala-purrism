/*
rules = [SimplifyCatsExpressions]

# `map` on a List is scala/collection/immutable/List#map(), not Cats syntax.
# Rewriting it to `.as` changes it to a call that does not exist without
# importing cats.syntax.all, so this file must come back unchanged.
 */
package golden

final class CatsNegativeListMap {
  def constant(values: List[Int]): List[Int] =
    values.map(_ => 42)

  def discard(values: List[Int]): List[Unit] =
    values.map(_ => ())
}
