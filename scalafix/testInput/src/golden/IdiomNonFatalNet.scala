/*
rules = [PreferEffectIdioms]
 */
package golden

final class IdiomNonFatalNet {
  def detach(listener: AutoCloseable): Unit =
    try listener.close()
    catch { case _: Throwable => () }

  def detachNamed(listener: AutoCloseable): Unit =
    try listener.close()
    catch { case error: Throwable => println(error) }

  def classify(value: Int): String =
    value match {
      case 0 => "zero"
      case _ => "other"
    }
}
