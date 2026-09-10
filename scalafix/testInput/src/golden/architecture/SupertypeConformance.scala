/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.supertypes.inscope.**"]
RequireArrowArchitecture.allowedConcreteTypePatterns = ["^scala/Int#$"]
 */
package golden.architecture.supertypes.inscope

import cats.arrow.Arrow
import golden.architecture.supertypes.NonConformingBase

trait Marker extends Serializable // zero members: conforming

trait Base[Step[_, _]: Arrow] {
  def handle: Step[Int, Int]
}

trait Derived[Step[_, _]: Arrow] extends Base[Step] with Marker // conforming

trait BadDerived extends NonConformingBase // assert: RequireArrowArchitecture
