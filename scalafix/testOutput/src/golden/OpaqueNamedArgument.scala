
package golden

opaque type FramePos = Long
object FramePos:
  def apply(value: Long): FramePos = value
  extension (self: FramePos) def value: Long = self

final case class Write(bytes: Int, framePosition: FramePos)

object Writes {

  def initial(): Write = Write(bytes = 0, framePosition = FramePos(-1L))

  def next(previous: Write): Write =
    Write(bytes = previous.bytes, framePosition = FramePos(previous.framePosition.value + 1L))
}
