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

# `choose`'s result merges two sources in the same `if`: `source.id` (the
# seed, closure-covered) and `other.label` (a plain `String` field, not
# covered). The result position is demoted out of the closure and kept
# concrete; `PropagateOpaqueType.merge-point` reports it once, and
# `source.id` is unwrapped (`.value`) at its one crossing out of the
# closure into that result.
#
# Deliberately NOT a local `val` for the merged value: routing the same
# merge through `val chosen: String = if (...) ... ; chosen` reproduces a
# real `PropagateOpaqueType` bug (recorded separately, not pinned here) --
# the unwrap patch lands on the `val`'s own binder instead of the flowing
# expression, emitting invalid syntax (`val chosen.value: String = ...`).
 */
package golden

final case class Slot(id: String, label: String)

object Slots {
  def choose(flag: Boolean, source: Slot, other: Slot): String = // assert: PropagateOpaqueType.merge-point
    if (flag) source.id else other.label
}
