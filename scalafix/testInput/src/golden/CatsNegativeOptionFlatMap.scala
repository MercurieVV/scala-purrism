/*
rules = [SimplifyCatsExpressions]

# Option has a Cats Monad instance, but `flatMap` here resolves to
# scala/Option#flatMap(), not to Cats syntax. `*>` is not available on the
# receiver as written, so this file must come back unchanged.
 */
package golden

final class CatsNegativeOptionFlatMap {
  def sequence(first: Option[Int], second: Option[String]): Option[String] =
    first.flatMap(_ => second)

  def constant(first: Option[Int]): Option[String] =
    first.map(_ => "done")
}
