
package golden

import cats.syntax.bifunctor._

object AbstractUnsupportedCatsApiGapFailsIndexAudit {
  private def process(e: Either[String, Int]): Either[Int, String] =
    e.bimap(_.length, _.toString) 
}
