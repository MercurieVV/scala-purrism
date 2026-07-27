/*
rules = [DisableSyntax]
 */
package golden

import scala.util.Try

object HktRewriterPinnedError {
  private def parse(value: Try[Int]): Try[Int] = value
}
