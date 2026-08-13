/*
rules = [PreferOptionIdioms]
 */
package golden

final class IdiomOptionNullGuard(sessions: java.util.HashMap[String, String]) {
  def label(handle: String): String = {
    val session = sessions.get(handle)
    if (session eq null) "unknown" else session.toUpperCase
  }
}
