/*
rules = [PreferContainerTypeclasses]
PreferContainerTypeclasses.crossFile = true
PreferContainerTypeclasses.widenPublic = true

# The *definition* half of the vetoed pair, identical in shape to
# WidenAppendDef -- what stops this one is the call site, not the signature.
 */
package crossfile

import cats.Show

object WidenVetoDef {
  def summarise[A: Show](rows: List[Int]): List[String] = // assert: PreferContainerTypeclasses
    rows.map(row => row.toString)
}
