/*
rules = [PropagateOpaqueType]

PropagateOpaqueType.types = [
  {
    name = "Ns"
    underlying = "scala/Long#"
    definitionFile = "scalafix/testInput/src/golden/OpaqueInfixOperand.scala"
    seeds = [ "golden/Span#startNs.", "golden/Span#endNs." ]
  }
]

# `a - b` is one call with one argument. The receiver and the operand cross the
# same foreign boundary, so both must be unwrapped -- unwrapping only the
# receiver leaves `a.value - b`, which does not typecheck.
 */
package golden

final case class Span(startNs: Long, endNs: Long)

object Spans {

  def durationUs(span: Span): Double = (span.endNs - span.startNs) / 1000.0

  def sample(): Span = Span(0L, 48L)
}
