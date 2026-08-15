/*
rules = [PreferPolymorphicCollections]
PreferPolymorphicCollections.crossFile = true
PreferPolymorphicCollections.widenPublic = true

# The *calling* half: the container is read off the argument this call already
# passes, and named.
 */
package crossfile

object WidenAppendUse {
  def labels: List[String] =
    WidenAppendDef.describe[Int](List(1, 2))
}
