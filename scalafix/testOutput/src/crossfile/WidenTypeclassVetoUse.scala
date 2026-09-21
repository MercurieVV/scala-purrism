
package crossfile

object WidenTypeclassVetoUse {
  def labels(flag: Boolean): List[String] =
    WidenTypeclassVetoDef.summarise[Int](if (flag) List(1, 2) else Nil)
}
