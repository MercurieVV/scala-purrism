/*
rules = [PropagateOpaqueType]

PropagateOpaqueType.types = [
  {
    name = "FramePos"
    underlying = "scala/Long#"
    definitionFile = "scalafix/testInput/src/golden/OpaqueNamedArgument.scala"
    seeds = [ "golden/Write#framePosition." ]
  }
]

# A literal reaching a wrapped parameter by name is the same genesis as one
# reaching it by position: `f(p = 0L)` must wrap the value, not the whole
# `p = 0L` assignment.
 */
package golden

final case class Write(bytes: Int, framePosition: Long)

object Writes {

  def initial(): Write = Write(bytes = 0, framePosition = -1L)

  def next(previous: Write): Write =
    Write(bytes = previous.bytes, framePosition = previous.framePosition + 1L)
}
