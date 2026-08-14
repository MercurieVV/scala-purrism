
package golden

import cats.FunctorFilter

// Not widened: summon-style body, see AbstractReducibleNonEmpty.
private def filter(xs: Option[Int]): Option[Int] = FunctorFilter[Option].filter(xs)(_ > 0)
