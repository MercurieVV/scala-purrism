/*
rules = [PreferOptionIdioms]
 */
package golden

final class IdiomIdempotentOption(sessions: java.util.HashMap[String, String]) {
  def label(handle: String): String =
    Option(sessions.get(handle)).fold("unknown")(session => session.toUpperCase)
}
