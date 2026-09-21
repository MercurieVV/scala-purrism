
package golden

opaque type SlotId = String
object SlotId:
  def apply(value: String): SlotId = value
  extension (self: SlotId) def value: String = self

final case class Slot(id: SlotId, label: String)

object Slots {
  def choose(flag: Boolean, source: Slot, other: Slot): String = 
    if (flag) source.id.value else other.label
}
