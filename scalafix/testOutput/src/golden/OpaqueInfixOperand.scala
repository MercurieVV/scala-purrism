
package golden

opaque type Ns = Long
object Ns:
  def apply(value: Long): Ns = value
  extension (self: Ns) def value: Long = self

final case class Span(startNs: Ns, endNs: Ns)

object Spans {

  def durationUs(span: Span): Double = (span.endNs.value - span.startNs.value) / 1000.0

  def sample(): Span = Span(Ns(0L), Ns(48L))
}
