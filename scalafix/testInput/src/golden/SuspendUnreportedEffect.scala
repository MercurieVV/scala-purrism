/*
rules = [SuspendSideEffects]
 */
package golden

import java.nio.file.{Files, Path}

final class SuspendUnreportedEffect {
  def record(target: Path, line: String): Unit = // assert: SuspendSideEffects
    Files.writeString(target, line)

  def stamp(): Long = // assert: SuspendSideEffects
    System.nanoTime()

  /** Twenty writes are one decision, so this reports once. */
  def header(out: java.io.DataOutputStream): Unit = { // assert: SuspendSideEffects
    out.writeBytes("RIFF")
    out.writeBytes("WAVE")
    out.writeBytes("fmt ")
  }
}
