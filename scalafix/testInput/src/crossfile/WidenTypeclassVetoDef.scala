/*
rules = [PreferPolymorphicTypeclasses]
PreferPolymorphicTypeclasses.crossFile = true
PreferPolymorphicTypeclasses.widenPublic = true
PreferPolymorphicTypeclasses.containers = []

# The *definition* half of the vetoed pair, identical in shape to
# WidenVetoDef (docs/design/PreferPolymorphicCollections.md's crossfile veto
# fixture) but routed at `PreferPolymorphicTypeclasses` via `containers = []`:
# what stops this one is the call site, not the signature.
 */
package crossfile

import cats.Show

object WidenTypeclassVetoDef {
  def summarise[A: Show](rows: List[Int]): List[String] = // assert: PreferPolymorphicTypeclasses.constructor-explicit-type-arguments
    rows.map(row => row.toString)
}
