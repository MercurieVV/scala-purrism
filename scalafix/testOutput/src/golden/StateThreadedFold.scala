
package golden

import cats.data.State
import cats.syntax.all.*

final class StateThreadedFold {
  def unique(names: List[String]): (Map[String, Int], List[String]) =
    names.traverse(name => State((seen: Map[String, Int]) => {val count = seen.getOrElse(name, 0)
        (seen.updated(name, count + 1), s"$name$count")})).run(Map.empty[String, Int]).value
}
