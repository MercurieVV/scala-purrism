
package golden

object AbstractConcretePatternMatch {
  private def count(xs: List[Int]): Int =
    xs match { 
      case Nil => 0
      case h :: t => 1 + count(t)
    }
}
