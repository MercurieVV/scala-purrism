/*
rules = [PreferHKTTypeclasses]
 */
package golden

import scala.util.Try

// Not widened: `Try(...)` is a call on the *companion*, not on a value of the
// abstracted type, so the body states no capability the signature could carry.
// Lifting it would mean rewriting the body to `F.fromTry(Try(...))`, which this
// rule does not do -- it only widens signatures.
private def parse(s: String): Try[Int] = Try(s.toInt)
