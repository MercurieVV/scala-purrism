/*
rules = [PropagateOpaqueType]

PropagateOpaqueType.types = [
  {
    name = "SlotId"
    underlying = "scala/Predef.String#"
    definitionFile = "scalafix/testInput/src/golden/OpaqueMergePoint.scala"
    seeds = [ "golden/Slot#id." ]
  }
]

# `chosen` receives the seed (`source.id`, closure-covered) and a plain
# literal (`"guest"`, not covered) from two branches of the same `if`, then
# flows into another `Slot.id` position -- a node the closure reached that
# also receives a value outside the closure. `PropagateOpaqueType.merge-point`
# reports it and leaves both call sites unwrapped.
 */
package golden

final case class Slot(id: String, label: String)

object Slots {
  def choose(flag: Boolean, source: Slot, other: Slot): Slot = {
    val chosen: String = // assert: PropagateOpaqueType.merge-point
      if (flag) source.id else other.label
    Slot(chosen, "picked")
  }
}
