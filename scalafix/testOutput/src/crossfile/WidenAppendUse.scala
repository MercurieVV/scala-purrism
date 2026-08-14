
package crossfile

object WidenAppendUse {
  def labels: List[String] =
    WidenAppendDef.describe[Int, List](List(1, 2))
}
