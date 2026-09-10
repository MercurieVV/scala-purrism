/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.supertypes\\.inscope.*"]
RequireArrowArchitecture.classes = ["cats\\.arrow\\..*", "scala\\.package\\.Serializable"]
 */
package golden.architecture.supertypes.inscope

import cats.arrow.Arrow
import golden.architecture.supertypes.NonConformingBase

// The old engine auto-detected a zero-member supertype (a marker/tag trait)
// as conforming by walking its members structurally. The new engine has no
// such structural recursion over supertypes -- a marker like Serializable is
// just another named type at the extends-clause position, so it needs an
// explicit classes entry the same as any other allowed type.
trait Marker extends Serializable // conforming: Serializable is whitelisted above

trait Base[Step[_, _]: Arrow] {
  def handle: Step[Int, Int]
}

trait Derived[Step[_, _]: Arrow] extends Base[Step] with Marker // conforming

trait BadDerived extends NonConformingBase // assert: RequireArrowArchitecture
