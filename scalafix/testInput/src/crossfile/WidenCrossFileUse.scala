/*
rules = [PreferPolymorphicCollections]
PreferPolymorphicCollections.crossFile = true
PreferPolymorphicCollections.widenPublic = true

# The *calling* half: the explicit type argument has to go, because `render`
# grows a second type parameter and inference can recover both from the
# argument list.
 */
package crossfile

object WidenCrossFileUse {
  def label: String =
    WidenCrossFileDef.render[Int](List(1, 2), "n=")
}
