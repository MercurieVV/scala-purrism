/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.scope = ["golden\\.architecture\\.slotforms.*"]
RequireArrowArchitecture.profile = "default"
RequireArrowArchitecture.profiles.default.classes = ["cats\\.arrow\\..*", "scala\\.Int", "scala\\.package\\.Either", "scala\\.Predef\\.String"]
 */
package golden.architecture.slotforms

import cats.arrow.Arrow
import cats.effect.Sync

// abstract-type-member slot form: conforming, same as the type-parameter form
trait AbstractMemberSlot {
  type Step[_, _]
  def stepArrow: Arrow[Step]
  def validate: Step[Int, Either[String, Int]]
}

// The old engine flagged this as a monomorphism violation (generic beyond
// the slot's own holes). The new engine dropped that rule -- what actually
// catches this now is that Sync (cats.effect.Sync, from the G[_]: Sync
// context bound) isn't a whitelisted type, same as any other disallowed
// concrete reference.
trait GenericMemberViolation[Step[_, _]: Arrow] {
  def step[G[_]: Sync]: Step[G[Int], Int] // assert: RequireArrowArchitecture.typeWhitelist
}
