/*
rules = [PreferPolymorphicTypeclasses]
 */
package golden

import cats.syntax.bifunctor._

object AbstractUnsupportedCatsApiGapFailsIndexAudit {
  // Silent: `Either` is a binary constructor, which v1 does not solve (see
  // gaps.tsv and docs/design/PreferPolymorphicTypeclasses.md item 6). Reported per
  // signature it would warn on every `Either` and `Map` in a codebase.
  private def process(e: Either[String, Int]): Either[Int, String] =
    e.bimap(_.length, _.toString)
}
