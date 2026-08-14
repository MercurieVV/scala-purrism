/*
rules = [PreferContainerTypeclasses]
PreferContainerTypeclasses.crossFile = true
PreferContainerTypeclasses.widenPublic = true

# The *definition* half of the appended pair. `A` reaches `describe` through
# evidence alone, so its call sites cannot drop their type arguments and expect
# inference to find `A` again -- they are given the new one instead. See
# WidenAppendUse.scala.
 */
package crossfile

import cats.Show

object WidenAppendDef {
  def describe[A: Show](rows: List[Int]): List[String] =
    rows.map(row => row.toString)
}
