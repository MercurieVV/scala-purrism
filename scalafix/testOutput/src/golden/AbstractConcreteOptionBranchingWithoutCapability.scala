
package golden

object AbstractConcreteOptionBranchingWithoutCapability {
  private def extract(o: Option[Int], d: Int): Int =
    if (o.isDefined) o.get else d 
}
