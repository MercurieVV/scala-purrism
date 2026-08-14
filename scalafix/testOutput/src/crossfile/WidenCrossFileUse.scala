
package crossfile

object WidenCrossFileUse {
  def label: String =
    WidenCrossFileDef.render(List(1, 2), "n=")
}
