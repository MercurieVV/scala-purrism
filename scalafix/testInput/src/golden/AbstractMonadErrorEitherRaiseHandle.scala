/*
rules = [PreferHKTTypeclasses]
 */
package golden

import scala.util.Try

private def parse(s: String): Try[Int] = Try(s.toInt)
