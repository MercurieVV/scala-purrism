package golden

opaque type TicketId = String
object TicketId:
  def apply(value: String): TicketId = value
  extension (self: TicketId) def value: String = self

final case class Ticket(id: TicketId, title: String)

object Tickets {

  // A varargs command list: heterogeneous, so it is a boundary rather than a
  // closure member, and values crossing into it must be unwrapped.
  def run(command: String*): Int = command.length

  def open(ticket: Ticket): Int = run("open", ticket.id.value)

  def create(): Ticket = Ticket(TicketId("T-1"), "first")
}
