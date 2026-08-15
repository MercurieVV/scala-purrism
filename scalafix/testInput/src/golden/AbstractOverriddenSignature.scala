/*
rules = [PreferPolymorphicTypeclasses]

PreferPolymorphicTypeclasses.widenPublic = true
 */
package golden

trait ToList {
  def apply[A](fa: List[A]): List[A]
}

object AbstractOverriddenSignature {

  /** Not widened: `apply[A, G[_]: Functor]` implements nothing. The signature
    * belongs to `ToList`, which is not this rule's to change.
    */
  val identityK: ToList = new ToList {
    override def apply[A](fa: List[A]): List[A] = fa.map(identity)
  }
}
