
package golden

import java.io.PrintWriter
import scala.util.Using

final class IdiomManualResource {
  def write(out: PrintWriter, line: String): Unit =
    Using.resource(out)(_ => out.println(line))
}
