
package golden

import scala.util.control.NonFatal
final class IdiomNonFatalNet {
  def detach(listener: AutoCloseable): Unit =
    try listener.close()
    catch { case NonFatal(_) => () }

  def detachNamed(listener: AutoCloseable): Unit =
    try listener.close()
    catch { case NonFatal(error) => println(error) }

  def classify(value: Int): String =
    value match {
      case 0 => "zero"
      case _ => "other"
    }
}
