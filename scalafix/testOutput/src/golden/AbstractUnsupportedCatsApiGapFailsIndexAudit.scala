/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.bifunctor._

object AbstractUnsupportedCatsApiGapFailsIndexAudit {
  private def process(e: Either[String, Int]): Either[Int, String] = // assert: PreferHKTTypeclasses
    e.bimap(_.length, _.toString)
}
