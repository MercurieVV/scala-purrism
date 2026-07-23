/*
rules = [PropagateOpaqueType]

PropagateOpaqueType.types = [
  {
    name = "TicketId"
    underlying = "scala/Predef.String#"
    definitionFile = "scalafix/testInput/src/golden/OpaqueBoundaries.scala"
    seeds = [ "golden/Ticket#id." ]
  }
]

# Stage 3: the value is created from a literal (wrap) and handed to a varargs
# command helper (unwrap), and the opaque type itself is emitted into this file.
 */
package golden

final case class Ticket(id: String, title: String)

object Tickets {

  // A varargs command list: heterogeneous, so it is a boundary rather than a
  // closure member, and values crossing into it must be unwrapped.
  def run(command: String*): Int = command.length

  def open(ticket: Ticket): Int = run("open", ticket.id)

  def create(): Ticket = Ticket("T-1", "first")
}
