/*
rules = [SuspendSideEffects]
 */
package golden

final class SuspendKeptOnRealtimePath {
  // purrism:keep host realtime callback; the audio thread cannot run an effect
  def render(frames: Int): Unit =
    System.arraycopy(new Array[Float](frames), 0, new Array[Float](frames), 0, frames)

  def report(frames: Int): Unit = // assert: SuspendSideEffects
    System.arraycopy(new Array[Float](frames), 0, new Array[Float](frames), 0, frames)
}
