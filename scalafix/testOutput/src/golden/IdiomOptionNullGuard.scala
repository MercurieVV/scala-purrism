
package golden

final class IdiomOptionNullGuard(sessions: java.util.HashMap[String, String]) {
  def label(handle: String): String = { Option(sessions.get(handle)).fold("unknown")(session => session.toUpperCase) }
}
