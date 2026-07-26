package fix

import scalafix.v1._
import scala.meta._

final class PreferHKTTypeclasses extends SemanticRule("PreferHKTTypeclasses") {
  override def fix(implicit doc: SemanticDocument): Patch = Patch.empty
}
