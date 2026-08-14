/*
rules = [PreferContainerTypeclasses]
PreferContainerTypeclasses.crossFile = true
PreferContainerTypeclasses.widenPublic = true

# The *definition* half. `render` declares a type parameter already, so a call
# site is free to write `render[Int](...)` -- and after widening that call names
# one type argument too few. See WidenCrossFileUse.scala for the calling half:
# both files must be rewritten in the same run or the result does not compile.
 */
package crossfile

object WidenCrossFileDef {
  def render[A](rows: List[A], prefix: String): String =
    prefix + rows.toList.size
}
