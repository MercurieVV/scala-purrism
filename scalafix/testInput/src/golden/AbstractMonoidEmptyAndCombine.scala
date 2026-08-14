/*
rules = [PreferHKTTypeclasses]

# The collections are `PreferContainerTypeclasses`' subject by default; these
# fixtures are about the shape, not about who owns `List`.
PreferHKTTypeclasses.containers = []
 */
package golden

import cats.Monoid

private def fold[A: Monoid](xs: List[A]): A = xs.foldLeft(Monoid[A].empty)(Monoid[A].combine)
