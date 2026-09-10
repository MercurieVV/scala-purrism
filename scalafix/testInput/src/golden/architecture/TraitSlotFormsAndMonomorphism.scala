/*
rules = [RequireArrowArchitecture]

RequireArrowArchitecture.packages = ["golden.architecture.slotforms.**"]
 */
package golden.architecture.slotforms

import cats.arrow.Arrow
import cats.effect.Sync

trait AbstractMemberSlot {
  type Step[_, _]
  def stepArrow: Arrow[Step]
  def validate: Step[Int, Either[String, Int]]
}

trait GenericMemberViolation[Step[_, _]: Arrow] {
  def step[G[_]: Sync]: Step[G[Int], Int] // assert: RequireArrowArchitecture
}
