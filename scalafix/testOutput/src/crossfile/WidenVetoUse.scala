
package crossfile

object WidenVetoUse {
  def labels(flag: Boolean): List[String] =
    WidenVetoDef.summarise[Int](if (flag) List(1, 2) else Nil)
}
