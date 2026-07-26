/*
rules = [PreferHKTTypeclasses]
 */
package golden

import cats.syntax.functor._

object AbstractConcreteEitherLeftSpecificWithoutBifunctor {
  private def handleError(e: Either[String, Int]): Either[Int, Int] = // assert: PreferHKTTypeclasses
    e.left.map(_.length)
}
