/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.functor._

object AbstractConcreteEitherLeftSpecificWithoutBifunctor {
  // Silent: binary constructor, see AbstractUnsupportedCatsApiGapFailsIndexAudit.
  private def handleError(e: Either[String, Int]): Either[Int, Int] =
    e.left.map(_.length)
}
