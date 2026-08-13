/*
rules = [PreferEffectIdioms]
 */
package golden

import java.io.PrintWriter

final class IdiomManualResource {
  def write(out: PrintWriter, line: String): Unit =
    try out.println(line) // assert: PreferEffectIdioms
    finally out.close()
}
