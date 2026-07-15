package fix

import scalafix.v1.Patch
import scalafix.v1.SemanticDocument
import scalafix.v1.SemanticRule

final class TypelevelPurrism extends SemanticRule("TypelevelPurrism") {
  override def fix(implicit doc: SemanticDocument): Patch =
    Patch.empty
}
