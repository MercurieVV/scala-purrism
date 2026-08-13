/*
rules = [PreferStateThreading]
 */
package golden

import cats.data.State
import cats.syntax.all.*

final class StateThreadedFold {
  def unique(names: List[String]): (Map[String, Int], List[String]) =
    names.foldLeft((Map.empty[String, Int], List.empty[String])) {
      case ((seen, out), name) =>
        val count = seen.getOrElse(name, 0)
        (seen.updated(name, count + 1), out :+ s"$name$count")
    }
}
