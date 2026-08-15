/*
rules = [PreferPolymorphicCollections]
PreferPolymorphicCollections.crossFile = true
PreferPolymorphicCollections.widenPublic = true

# The *calling* half that vetoes it: the argument is a conditional, so no
# container can be read off it, and a call site that cannot be told the new type
# argument is one the widening must not leave behind.
 */
package crossfile

object WidenVetoUse {
  def labels(flag: Boolean): List[String] =
    WidenVetoDef.summarise[Int](if (flag) List(1, 2) else Nil)
}
